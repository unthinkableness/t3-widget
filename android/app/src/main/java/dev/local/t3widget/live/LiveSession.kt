package dev.local.t3widget.live

import android.content.Context
import android.util.Log
import dev.local.t3widget.data.ApiException
import dev.local.t3widget.data.EndpointResolver
import dev.local.t3widget.data.ErrorKind
import dev.local.t3widget.data.NetClass
import dev.local.t3widget.data.RemoteT3Repository
import dev.local.t3widget.data.Settings
import dev.local.t3widget.data.ShellModel
import dev.local.t3widget.data.Store
import dev.local.t3widget.data.T3Api
import dev.local.t3widget.data.ThreadMapping
import dev.local.t3widget.data.UsageBar
import dev.local.t3widget.data.currentNetClass
import dev.local.t3widget.data.objects
import dev.local.t3widget.work.Refresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * One long-lived, authenticated WebSocket to `/ws` that keeps the widget current.
 *
 * Stream: `orchestration.subscribeShell` with `afterSequence` = the HTTP snapshot's `snapshotSequence` and
 * `httpSnapshotFallback: true`. The server then replays/streams only deltas (`thread.updated`, `thread.removed`,
 * `project.*`) and answers `snapshot-required` when it can't resume, in which case we re-fetch the ~20 KB gzip
 * HTTP snapshot instead of receiving a large socket snapshot. Deltas are applied to [ShellModel]; the widget is
 * recomputed at most every [DEBOUNCE_MS]. Effect-RPC flow control: every stream Chunk must be Ack'ed or the
 * server stops sending. We send app-level `Ping` (server answers `Pong`) plus OkHttp WebSocket pings, and treat
 * silence as a dead socket.
 *
 * Usage limits are re-read every 5 min (15 min while the screen is off) with a short `subscribeServerConfig`
 * request on the same socket. A full HTTP resync runs every 30 min as a safety net.
 *
 * All state is confined to the coroutine running [run]; the OkHttp listener only posts into a channel.
 */
class LiveSession(private val context: Context) {

    enum class Kick { NETWORK, USER_PRESENT, MANUAL, SETTINGS }

    private val kicks = Channel<Kick>(Channel.CONFLATED)
    private val store = Store(context)
    private val model = ShellModel()
    private var usage: List<UsageBar> = emptyList()
    private var usageAt = 0L
    private var nextId = 1L

    @Volatile var screenOn: Boolean = true
    /** Called with true/false as the socket connects/disconnects. */
    var onConnectedChanged: (Boolean) -> Unit = {}

    fun kick(k: Kick) {
        kicks.trySend(k)
    }

    /** Runs until cancelled or until live updates are no longer wanted (then returns). */
    suspend fun run() {
        usage = store.state().usage
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val settings = store.settings()
            if (!settings.remote || !settings.liveUpdates) return
            val startedAt = System.currentTimeMillis()
            try {
                connectAndStream(settings)
                failures = 0 // returned normally: settings changed or a deliberate reconnect
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                onConnectedChanged(false)
                val (kind, msg) = Refresher.classify(context, t)
                Log.i(TAG, "live session ended: $kind $msg (${t::class.java.simpleName})")
                Refresher.publishError(context, kind, msg)
                if (System.currentTimeMillis() - startedAt > 120_000) failures = 0
                val waitMs = when (kind) {
                    ErrorKind.AUTH, ErrorKind.SCOPE, ErrorKind.NOT_SET_UP -> 30 * 60_000L
                    ErrorKind.PROTOCOL -> 60 * 60_000L
                    ErrorKind.OFFLINE -> 15 * 60_000L // a NETWORK kick ends this early
                    else -> backoffMs(failures)
                }
                failures++
                // Wait for the backoff, or wake early on network back / unlock / manual refresh / new settings.
                withTimeoutOrNull(waitMs) { kicks.receive() }
            }
        }
    }

    private suspend fun connectAndStream(settings: Settings) {
        val repo = RemoteT3Repository(context, settings)
        val first = repo.fetchShell()
        model.reset(first.shell)
        val resolved = first.resolved
        repo.usage(resolved.endpoint)?.let { usage = it; usageAt = System.currentTimeMillis() }
        publish(resolved, force = true)
        repo.refreshTunnelHostname(resolved.endpoint)
        stream(settings, repo, resolved)
    }

    private sealed interface Ev {
        data object Open : Ev
        data class Text(val text: String) : Ev
        data class Closed(val code: Int, val reason: String) : Ev
        data class Failed(val t: Throwable, val code: Int?) : Ev
    }

    private suspend fun stream(settings: Settings, repo: RemoteT3Repository, start: EndpointResolver.Resolved) {
        var resolved = start
        val netAtConnect = currentNetClass(context)
        val events = Channel<Ev>(Channel.UNLIMITED)
        val client = T3Api.client.newBuilder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val ws = client.newWebSocket(
            T3Api.authed(T3Api.wsUrl(resolved.endpoint.baseUrl), settings.token).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { events.trySend(Ev.Open) }
                override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(Ev.Text(text)) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null); events.trySend(Ev.Closed(code, reason))
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.trySend(Ev.Closed(code, reason)) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    events.trySend(Ev.Failed(t, response?.code))
                }
            },
        )

        fun subscribe(): String {
            val id = (nextId++).toString()
            ws.send(subscribeShellRequest(id, model.sequence))
            return id
        }

        var shellReq = subscribe()
        var usageReq: String? = null
        var connected = false
        var lastRx = System.currentTimeMillis()
        var lastPing = lastRx
        var lastResync = lastRx
        var publishAt = 0L // 0 = nothing pending

        try {
            while (true) {
                val now = System.currentTimeMillis()
                // ---- timers
                if (publishAt in 1..now) {
                    publish(resolved); publishAt = 0
                }
                if (now - lastRx > SILENCE_MS) throw IOException("Socket silent")
                if (now - lastPing >= PING_MS) {
                    ws.send(PING); lastPing = now
                }
                if (connected && usageReq == null && now - usageAt >= usageIntervalMs()) {
                    usageReq = (nextId++).toString()
                    ws.send(T3Api.serverConfigRequest(usageReq))
                    usageAt = now // attempt time; a failed attempt waits a full interval
                }
                if (connected && now - lastResync >= RESYNC_MS) {
                    lastResync = now
                    resync(settings.token, resolved)
                    publishAt = now
                }

                val deadlines = listOfNotNull(
                    publishAt.takeIf { it > 0 },
                    lastPing + PING_MS,
                    lastRx + SILENCE_MS,
                    if (connected && usageReq == null) usageAt + usageIntervalMs() else null,
                    if (connected) lastResync + RESYNC_MS else null,
                )
                val waitMs = (deadlines.min() - System.currentTimeMillis()).coerceIn(10, 60_000)
                val ev: Any? = withTimeoutOrNull(waitMs) {
                    select<Any> {
                        events.onReceive { it }
                        kicks.onReceive { it }
                    }
                }

                when (ev) {
                    null -> Unit
                    is Ev.Open -> {
                        connected = true
                        lastRx = System.currentTimeMillis()
                        onConnectedChanged(true)
                        publishAt = System.currentTimeMillis()
                    }
                    is Ev.Text -> {
                        lastRx = System.currentTimeMillis()
                        val msg = runCatching { JSONObject(ev.text) }.getOrNull() ?: continue
                        val tag = msg.optString("_tag")
                        val rid = msg.optString("requestId")
                        when {
                            tag == "Ping" -> ws.send(PONG)
                            tag == "Pong" -> Unit
                            tag == "Chunk" && rid == shellReq -> {
                                var needSnapshot = false
                                for (v in msg.optJSONArray("values").objects()) {
                                    if (model.apply(v) == ShellModel.Applied.SnapshotRequired) needSnapshot = true
                                }
                                ws.send(ack(rid))
                                if (needSnapshot) {
                                    // Server can't resume from our cursor: HTTP snapshot, then resubscribe from it.
                                    ws.send(interrupt(shellReq))
                                    resync(settings.token, resolved)
                                    lastResync = System.currentTimeMillis()
                                    shellReq = subscribe()
                                }
                                if (publishAt == 0L) publishAt = System.currentTimeMillis() + DEBOUNCE_MS
                            }
                            tag == "Chunk" && rid == usageReq -> {
                                ws.send(interrupt(rid))
                                usageReq = null
                                T3Api.configFromChunk(msg)?.let {
                                    usage = ThreadMapping.usageBars(it)
                                    usageAt = System.currentTimeMillis()
                                }
                                // Also a heartbeat: refreshes updatedAt so a quiet-but-live widget never looks stale.
                                publish(resolved, force = true)
                            }
                            tag == "Exit" && rid == shellReq -> {
                                val fail = T3Api.rpcFailure(msg)
                                if (fail.kind == ErrorKind.SCOPE) throw fail
                                throw IOException("Shell stream ended")
                            }
                            tag == "Exit" && rid == usageReq -> {
                                usageReq = null
                                if (msg.optJSONObject("exit")?.optString("_tag") == "Failure") {
                                    Log.w(TAG, "usage request failed")
                                }
                            }
                            tag == "Defect" || tag == "ClientProtocolError" ->
                                throw ApiException(ErrorKind.SERVER, "RPC $tag")
                        }
                    }
                    is Ev.Closed -> throw IOException("Socket closed ${ev.code}")
                    is Ev.Failed -> throw if (ev.code != null) T3Api.statusException(ev.code) else ev.t
                    is Kick -> when (ev) {
                        Kick.SETTINGS -> return
                        Kick.NETWORK -> {
                            val net = currentNetClass(context)
                            if (net == NetClass.NONE) throw ApiException(ErrorKind.OFFLINE, "Offline")
                            // Network type changed (e.g. got home → prefer LAN, left home → LAN is dead): re-resolve.
                            if (net != netAtConnect) return
                            ws.send(PING); lastPing = System.currentTimeMillis()
                        }
                        Kick.USER_PRESENT -> {
                            ws.send(PING); lastPing = System.currentTimeMillis()
                            if (connected && usageReq == null && System.currentTimeMillis() - usageAt > 2 * 60_000L) {
                                usageAt = 0 // request on the next loop pass
                            }
                            publishAt = System.currentTimeMillis()
                        }
                        Kick.MANUAL -> {
                            resync(settings.token, resolved)
                            lastResync = System.currentTimeMillis()
                            if (connected && usageReq == null) usageAt = 0
                            publishAt = System.currentTimeMillis()
                        }
                    }
                }
            }
        } finally {
            onConnectedChanged(false)
            runCatching { ws.close(1000, null) }
            ws.cancel()
            events.close()
        }
    }

    /** HTTP snapshot from the endpoint in use (no endpoint change mid-stream). */
    private suspend fun resync(token: String, resolved: EndpointResolver.Resolved) {
        model.reset(T3Api.shell(resolved.endpoint.baseUrl, token))
    }

    private suspend fun publish(resolved: EndpointResolver.Resolved, force: Boolean = false) {
        val next = RemoteT3Repository.stateFrom(model.summary(), usage, resolved, live = true)
        val prev = store.state()
        if (!force && next.sameContentAs(prev) && next.updatedAt - prev.updatedAt < 60_000) return
        Refresher.publish(context, next)
    }

    private fun usageIntervalMs() = if (screenOn) 5 * 60_000L else 15 * 60_000L

    companion object {
        private const val TAG = "T3Live"
        const val DEBOUNCE_MS = 1_500L
        private const val PING_MS = 25_000L
        private const val SILENCE_MS = 80_000L
        private const val RESYNC_MS = 30 * 60_000L
        private const val PING = """{"_tag":"Ping"}"""
        private const val PONG = """{"_tag":"Pong"}"""

        fun ack(id: String) = """{"_tag":"Ack","requestId":"$id"}"""
        fun interrupt(id: String) = """{"_tag":"Interrupt","requestId":"$id"}"""

        fun subscribeShellRequest(id: String, afterSequence: Long): String = JSONObject()
            .put("_tag", "Request").put("id", id).put("tag", "orchestration.subscribeShell")
            .put("headers", JSONArray())
            .put("payload", JSONObject().apply {
                if (afterSequence >= 0) {
                    put("afterSequence", afterSequence)
                    put("httpSnapshotFallback", true)
                }
                put("omitIdleSubagents", true)
                put("omitOlderSettled", true)
            }).toString()

        /** 2 s, 5 s, 10 s, 20 s, 40 s, 60 s, then 2 min and 5 min, ±20 % jitter. */
        fun backoffMs(failures: Int): Long {
            val steps = longArrayOf(2_000, 5_000, 10_000, 20_000, 40_000, 60_000, 120_000, 300_000)
            val base = steps[failures.coerceIn(0, steps.size - 1)]
            return (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
        }
    }
}
