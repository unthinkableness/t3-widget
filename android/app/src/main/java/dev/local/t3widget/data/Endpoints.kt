package dev.local.t3widget.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import java.io.IOException

enum class EndpointKind(val label: String) { TUNNEL("tunnel"), LAN("LAN") }

data class Endpoint(val kind: EndpointKind, val baseUrl: String)

/** What the phone is connected to right now. */
enum class NetClass { NONE, WIFI, OTHER }

/**
 * Endpoint choice:
 *  - On Wi-Fi (or Ethernet) with a LAN URL: LAN first, with a 1.5 s probe, then the tunnel.
 *    At home this skips the internet round trip; elsewhere the LAN probe fails fast.
 *  - Otherwise: tunnel first, then LAN (covers a VPN that routes the LAN).
 *  - The endpoint that last worked on this kind of network is tried first ("remembered").
 * Each candidate is probed with the unauthenticated `/.well-known/t3/environment`. The token is only sent to a
 * LAN address whose environmentId matches the one already seen (protects it on someone else's Wi-Fi).
 */
object EndpointOrder {
    const val LAN_PROBE_MS = 1_500L
    const val TUNNEL_PROBE_MS = 6_000L

    fun candidates(serverUrl: String, lanUrl: String, net: NetClass, remembered: EndpointKind?): List<Endpoint> {
        val tunnel = SetupLink.normalizeBaseUrl(serverUrl)?.let { Endpoint(EndpointKind.TUNNEL, it) }
        val lan = SetupLink.normalizeBaseUrl(lanUrl)?.let { Endpoint(EndpointKind.LAN, it) }
        val base = if (net == NetClass.WIFI) listOfNotNull(lan, tunnel) else listOfNotNull(tunnel, lan)
        val first = base.firstOrNull { it.kind == remembered } ?: return base
        return listOf(first) + (base - first)
    }

    fun probeTimeout(e: Endpoint) = if (e.kind == EndpointKind.LAN) LAN_PROBE_MS else TUNNEL_PROBE_MS
}

fun currentNetClass(context: Context): NetClass {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetClass.OTHER
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetClass.NONE
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetClass.NONE
    return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        NetClass.WIFI else NetClass.OTHER
}

/** Picks a working endpoint, caches environmentId, remembers the winner per network class. */
class EndpointResolver(private val context: Context, private val store: Store) {

    data class Resolved(val endpoint: Endpoint, val environmentId: String)

    /** @throws ApiException OFFLINE / UNREACHABLE / PROTOCOL / NOT_SET_UP */
    suspend fun resolve(settings: Settings, exclude: Set<EndpointKind> = emptySet()): Resolved {
        val net = currentNetClass(context)
        if (net == NetClass.NONE) throw ApiException(ErrorKind.OFFLINE, "Offline")
        val cache = store.connectionCache()
        val remembered = cache.rememberedFor(net)
        val list = EndpointOrder.candidates(settings.serverUrl, settings.lanUrl, net, remembered)
            .filterNot { it.kind in exclude }
        if (list.isEmpty()) throw ApiException(ErrorKind.NOT_SET_UP, "No server URL")

        var last: Exception? = null
        for (e in list) {
            try {
                val env = T3Api.environment(e.baseUrl, EndpointOrder.probeTimeout(e))
                val id = env.optString("environmentId")
                if (e.kind == EndpointKind.LAN && cache.environmentId != null && cache.environmentId != id) {
                    last = ApiException(ErrorKind.UNREACHABLE, "LAN address is a different T3 server")
                    continue
                }
                store.saveConnection(cache.copy(environmentId = id).remember(net, e.kind))
                return Resolved(e, id)
            } catch (c: CancellationException) {
                throw c
            } catch (ex: ApiException) {
                if (ex.kind == ErrorKind.PROTOCOL && e.kind == EndpointKind.TUNNEL) throw ex
                last = ex
            } catch (ex: IOException) {
                last = ex
            }
        }
        val l = last
        if (l is ApiException && l.kind == ErrorKind.PROTOCOL) throw l
        throw ApiException(ErrorKind.UNREACHABLE, "PC unreachable")
    }
}
