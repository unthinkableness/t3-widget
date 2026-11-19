package dev.local.t3widget.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A failure with a widget-facing category. */
class ApiException(val kind: ErrorKind, message: String, val httpCode: Int = 0) : Exception(message)

/**
 * Low-level calls to the T3 Code server (v0.1.10, orchestration protocol 2). See ../api/API.md.
 * These are undocumented T3 internals and may change between releases.
 */
object T3Api {
    const val PROTOCOL = 2

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Short-timeout client for reachability probes (LAN must answer fast to be preferred). */
    private fun probeClient(timeoutMs: Long) = client.newBuilder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs + 500, TimeUnit.MILLISECONDS)
        .build()

    /** `GET /.well-known/t3/environment` (no auth): environmentId, serverVersion, orchestrationProtocolVersion. */
    suspend fun environment(base: String, timeoutMs: Long = 6_000): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/.well-known/t3/environment").build()
        probeClient(timeoutMs).newCall(req).execute().use { res ->
            checkStatus(res)
            val o = jsonBody(res)
            if (o.optString("environmentId").isBlank()) throw ApiException(ErrorKind.PROTOCOL, "Not a T3 server")
            val proto = o.optInt("orchestrationProtocolVersion", PROTOCOL)
            if (proto != PROTOCOL) throw ApiException(ErrorKind.PROTOCOL, "T3 protocol $proto; update widget")
            o
        }
    }

    /** `GET /api/orchestration/shell` (gzip, ~20 KB): threads + projects + snapshotSequence. */
    suspend fun shell(base: String, token: String): JSONObject = withContext(Dispatchers.IO) {
        val req = authed("$base/api/orchestration/shell?omitIdleSubagents=1&omitOlderSettled=1", token)
            .header("x-t3-orchestration-protocol", "$PROTOCOL")
            .build()
        client.newCall(req).execute().use { res ->
            checkStatus(res)
            jsonBody(res)
        }
    }

    /** `GET /api/connect/link-state` (needs relay:read): tunnelHostname, reachability.status. */
    suspend fun linkState(base: String, token: String): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(authed("$base/api/connect/link-state", token).build()).execute().use { res ->
            checkStatus(res)
            jsonBody(res)
        }
    }

    fun authed(url: String, token: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    fun wsUrl(base: String) = base.replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") +
        "/ws?orchestrationProtocol=$PROTOCOL"

    /** Payload for `subscribeServerConfig` (first chunk = ~70 KB snapshot incl. usage limits). */
    fun serverConfigRequest(id: String): String = JSONObject()
        .put("_tag", "Request").put("id", id).put("tag", "subscribeServerConfig")
        .put("headers", JSONArray())
        .put(
            "payload", JSONObject()
                .put("workspaceSnapshotsOnDemand", true)
                .put("providerCatalogsOnDemand", true)
                .put("providerCatalogDigests", true)
        ).toString()

    /** One short WebSocket exchange: subscribeServerConfig, first chunk, Interrupt, close. */
    suspend fun serverConfig(base: String, token: String): JSONObject = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine<JSONObject> { cont ->
            val req = authed(wsUrl(base), token).build()
            val ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(serverConfigRequest("1"))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (msg.optString("_tag") == "Ping") {
                        webSocket.send("""{"_tag":"Pong"}"""); return
                    }
                    if (msg.optString("requestId") != "1" || !cont.isActive) return
                    if (msg.optString("_tag") == "Chunk") {
                        val config = configFromChunk(msg)
                        webSocket.send("""{"_tag":"Interrupt","requestId":"1"}""")
                        webSocket.close(1000, null)
                        if (config != null) cont.resume(config)
                        else cont.resumeWithException(ApiException(ErrorKind.PROTOCOL, "No config snapshot"))
                    } else {
                        webSocket.close(1000, null)
                        cont.resumeWithException(rpcFailure(msg))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resumeWithException(
                        if (response != null) statusException(response.code) else t
                    )
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }
    } ?: throw IOException("Usage request timed out") // not a CancellationException: callers must not stop

    fun configFromChunk(msg: JSONObject): JSONObject? =
        msg.optJSONArray("values").objects().firstOrNull { it.optString("type") == "snapshot" }?.optJSONObject("config")

    fun rpcFailure(msg: JSONObject): ApiException {
        val text = msg.toString()
        return if (text.contains("EnvironmentAuthorizationError") || text.contains("insufficient_scope"))
            ApiException(ErrorKind.SCOPE, "Token lacks orchestration:read")
        else ApiException(ErrorKind.SERVER, "RPC ${msg.optString("_tag")}")
    }

    // ------------------------------------------------------------------ helpers

    private fun jsonBody(res: Response): JSONObject {
        val type = res.header("content-type").orEmpty()
        // Unknown /api paths fall through to the SPA's index.html with 200.
        if (!type.contains("json")) throw ApiException(ErrorKind.PROTOCOL, "Not a T3 server")
        val body = res.body?.string() ?: throw IOException("Empty body")
        return runCatching { JSONObject(body) }.getOrElse { throw ApiException(ErrorKind.PROTOCOL, "Bad JSON from server") }
    }

    private fun checkStatus(res: Response) {
        if (!res.isSuccessful) throw statusException(res.code)
    }

    fun statusException(code: Int) = when (code) {
        401 -> ApiException(ErrorKind.AUTH, "Re-pair needed", code)
        403 -> ApiException(ErrorKind.SCOPE, "Token lacks permission", code)
        426 -> ApiException(ErrorKind.PROTOCOL, "T3 protocol changed", code)
        502, 503, 504, 530 -> ApiException(ErrorKind.UNREACHABLE, "PC unreachable ($code)", code)
        else -> ApiException(ErrorKind.SERVER, "Server error $code", code)
    }
}
