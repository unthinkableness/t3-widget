package dev.local.t3widget

import dev.local.t3widget.data.ShellModel
import dev.local.t3widget.data.T3Api
import dev.local.t3widget.data.ThreadMapping
import dev.local.t3widget.data.objects
import dev.local.t3widget.live.LiveSession
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Optional check against a real T3 server. Skipped unless T3_TOKEN_FILE points at a file holding a token
 * (orchestration:read, optionally relay:read). T3_BASE_URL defaults to the local server. The token is never printed.
 *
 *   $env:T3_TOKEN_FILE="…"; $env:T3_BASE_URL="https://….t3coderelay.com"; .\build.ps1 testDebugUnitTest
 */
class LiveServerTest {
    private val tokenFile = System.getenv("T3_TOKEN_FILE")
    private val base = (System.getenv("T3_BASE_URL") ?: "http://127.0.0.1:3773").trimEnd('/')
    private val streamSeconds = (System.getenv("T3_STREAM_SECONDS") ?: "45").toLong()

    @Test fun apiAndShellStream() = runBlocking {
        assumeTrue("T3_TOKEN_FILE not set", tokenFile != null && File(tokenFile).exists())
        val token = File(tokenFile!!).readText().trim()

        val env = T3Api.environment(base)
        println("environmentId=${env.getString("environmentId")} server=${env.optString("serverVersion")}")

        val ls = runCatching { T3Api.linkState(base, token) }
        println("link-state: " + ls.fold({ "tunnelHostname=${it.optString("tunnelHostname")} reach=${it.optJSONObject("reachability")?.optString("status")}" }, { "failed: ${it.message}" }))

        val usage = ThreadMapping.usageBars(T3Api.serverConfig(base, token))
        println("usage bars: $usage")
        assertTrue(usage.isNotEmpty())

        val model = ShellModel()
        model.reset(T3Api.shell(base, token))
        val startSeq = model.sequence
        println("snapshot seq=$startSeq threads=${model.threadCount} summary=${model.summary()}")

        // Same wire exchange as LiveSession.stream(): subscribeShell from the HTTP cursor, Ack every chunk.
        val q = LinkedBlockingQueue<String>()
        val ws = T3Api.client.newBuilder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS).build()
            .newWebSocket(T3Api.authed(T3Api.wsUrl(base), token).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { q.put(text) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { q.put("""{"_tag":"FAIL","m":"${t.message} ${response?.code}"}""") }
            })
        ws.send(LiveSession.subscribeShellRequest("1", startSeq))
        val kinds = HashMap<String, Int>()
        var chunks = 0
        var pongs = 0
        var lastPing = 0L
        val end = System.currentTimeMillis() + streamSeconds * 1000
        while (System.currentTimeMillis() < end) {
            if (System.currentTimeMillis() - lastPing > 20_000) { ws.send("""{"_tag":"Ping"}"""); lastPing = System.currentTimeMillis() }
            val text = q.poll(1, TimeUnit.SECONDS) ?: continue
            val msg = JSONObject(text)
            when (msg.optString("_tag")) {
                "Ping" -> ws.send("""{"_tag":"Pong"}""")
                "Pong" -> pongs++
                "Chunk" -> {
                    chunks++
                    for (v in msg.optJSONArray("values").objects()) {
                        kinds.merge(v.optString("kind"), 1, Int::plus)
                        model.apply(v)
                    }
                    ws.send(LiveSession.ack(msg.optString("requestId")))
                }
                "FAIL" -> error("socket failed: ${msg.optString("m")}")
                else -> println("other frame: ${msg.optString("_tag")}")
            }
        }
        ws.send(LiveSession.interrupt("1"))
        ws.close(1000, null)
        println("stream: chunks=$chunks pongs=$pongs kinds=$kinds seq $startSeq -> ${model.sequence}")
        assertTrue("server answers app-level Ping", pongs > 0)

        // Deltas applied to the model must agree with a fresh HTTP snapshot.
        Thread.sleep(2_000)
        val live = model.summary()
        val fresh = ThreadMapping.summarize(T3Api.shell(base, token))
        println("delta model=$live\nfresh http =$fresh")
        assertEquals(fresh.running, live.running)
        assertEquals(fresh.needsYou, live.needsYou)
        assertEquals(fresh.topThreadId, live.topThreadId)
    }
}
