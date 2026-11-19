package dev.local.t3widget.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.IOException

/**
 * Talks to the T3 Code server (v0.1.10, orchestration protocol 2). See ../api/API.md.
 *
 *  - Endpoint: tunnel / LAN chosen by [EndpointResolver] (probe `/.well-known/t3/environment`).
 *  - Threads: GET /api/orchestration/shell (plain HTTP, gzip).
 *  - Usage limits: one short WebSocket exchange on /ws (subscribeServerConfig, first chunk, close).
 *  - Tunnel hostname: GET /api/connect/link-state when the token has relay:read.
 */
class RemoteT3Repository(
    context: Context,
    private val settings: Settings,
) : T3Repository {

    private val store = Store(context)
    private val resolver = EndpointResolver(context.applicationContext, store)

    data class ShellResult(val resolved: EndpointResolver.Resolved, val shell: JSONObject)

    override suspend fun fetch(previous: WidgetState): WidgetState {
        if (!settings.configured) throw ApiException(ErrorKind.NOT_SET_UP, "Not set up")
        val r = fetchShell()
        // Usage bars are a bonus: if only the socket fails, keep the previous bars.
        val usage = usage(r.resolved.endpoint)
        val sum = ThreadMapping.summarize(r.shell)
        refreshTunnelHostname(r.resolved.endpoint)
        return stateFrom(sum, usage ?: previous.usage, r.resolved, live = false)
    }

    /** Resolve an endpoint and fetch the shell; on a network failure, fail over to the other endpoint once. */
    suspend fun fetchShell(): ShellResult {
        requireToken()
        var resolved = resolver.resolve(settings)
        return try {
            ShellResult(resolved, T3Api.shell(resolved.endpoint.baseUrl, settings.token))
        } catch (e: IOException) {
            resolved = resolver.resolve(settings, exclude = setOf(resolved.endpoint.kind))
            ShellResult(resolved, T3Api.shell(resolved.endpoint.baseUrl, settings.token))
        }
    }

    /** Usage bars, or null if the WebSocket call failed (auth failures still throw). */
    suspend fun usage(endpoint: Endpoint): List<UsageBar>? = try {
        ThreadMapping.usageBars(T3Api.serverConfig(endpoint.baseUrl, settings.token))
    } catch (c: CancellationException) {
        throw c
    } catch (e: ApiException) {
        if (e.kind == ErrorKind.AUTH) throw e else null
    } catch (e: Exception) {
        null
    }

    /**
     * If the token has relay:read, follow T3 Connect tunnel hostname changes (throttled to every 10 min;
     * after a 403 only once a day). Only replaces a URL that already looks like a T3 tunnel (or is empty),
     * so a hand-entered Tailscale URL is never overwritten.
     */
    suspend fun refreshTunnelHostname(endpoint: Endpoint, now: Long = System.currentTimeMillis()) {
        val c = store.connectionCache()
        if (now - c.linkStateCheckedAt < 10 * 60_000L) return
        if (c.relayScopeMissingAt > 0 && now - c.relayScopeMissingAt < 24 * 3_600_000L) return
        try {
            val ls = T3Api.linkState(endpoint.baseUrl, settings.token)
            val status = ls.optJSONObject("reachability")?.optStringOrNull("status")
            store.saveConnection(store.connectionCache().copy(linkStateCheckedAt = now, relayScopeMissingAt = 0, tunnelStatus = status))
            val newUrl = TunnelHost.updatedServerUrl(settings.serverUrl, ls.optStringOrNull("tunnelHostname"))
            if (newUrl != null) store.setServerUrl(newUrl)
        } catch (c2: CancellationException) {
            throw c2
        } catch (e: ApiException) {
            if (e.kind == ErrorKind.SCOPE) {
                store.saveConnection(store.connectionCache().copy(linkStateCheckedAt = now, relayScopeMissingAt = now))
            }
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private fun requireToken() {
        if (settings.token.isBlank()) throw ApiException(ErrorKind.NOT_SET_UP, "No token")
    }

    companion object {
        fun stateFrom(sum: ThreadSummary, usage: List<UsageBar>, resolved: EndpointResolver.Resolved, live: Boolean) =
            WidgetState(
                running = sum.running,
                needsYou = sum.needsYou,
                topNeedsYouTitle = sum.topTitle,
                usage = usage,
                updatedAt = System.currentTimeMillis(),
                error = null,
                topNeedsYouThreadId = sum.topThreadId,
                environmentId = resolved.environmentId,
                errorKind = null,
                via = resolved.endpoint.kind.label,
                live = live,
            )
    }
}

/** Decides whether a link-state `tunnelHostname` should replace the saved server URL. Pure, unit-tested. */
object TunnelHost {
    private val HOST = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}\\.)+[A-Za-z]{2,}$")

    fun updatedServerUrl(current: String, tunnelHostname: String?): String? {
        val host = tunnelHostname?.trim()?.lowercase()?.takeIf { HOST.matches(it) } ?: return null
        val next = "https://$host"
        val cur = SetupLink.normalizeBaseUrl(current)
        if (cur == null) return if (current.isBlank()) next else null
        if (cur.equals(next, ignoreCase = true)) return null
        val curHost = cur.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return if (cur.startsWith("https://") && parent(curHost) == parent(host)) next else null
    }

    private fun parent(h: String) = h.split('.').takeLast(2).joinToString(".")
}
