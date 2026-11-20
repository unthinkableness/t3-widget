package dev.local.t3widget.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * One usage/limit bar. [urgency] ranks bars by use and projected use at reset.
 */
data class UsageBar(
    val label: String,
    val usedPercent: Double,
    val urgency: Int = usedPercent.toInt(),
    val timing: UsageWindowTiming? = null,
) {
    val remainingFraction: Float get() = ((100.0 - usedPercent) / 100.0).coerceIn(0.0, 1.0).toFloat()
    val percentLeft: Int get() = ((100.0 - usedPercent).coerceIn(0.0, 100.0)).roundToInt()
}

data class UsageWindowTiming(val resetsAt: Long, val durationMs: Long) {
    fun evenPaceRemaining(now: Long): Double =
        ((resetsAt.toDouble() - now) / durationMs).coerceIn(0.0, 1.0)
}

/** Why the last refresh failed. Drives the widget's wording and colour. */
enum class ErrorKind {
    /** 401: token expired or revoked. */
    AUTH,
    /** 403: token lacks orchestration:read. */
    SCOPE,
    /** 426 or an unexpected payload: the T3 server changed its protocol. */
    PROTOCOL,
    /** The phone has no network. Old values stay, shown muted. */
    OFFLINE,
    /** The phone is online but neither the tunnel nor the LAN address answered. */
    UNREACHABLE,
    /** 5xx or another server-side failure. */
    SERVER,
    /** No server URL or token yet. */
    NOT_SET_UP,
    OTHER;

    /** Offline-ish errors keep the old numbers and are drawn muted instead of red. */
    val soft: Boolean get() = this == OFFLINE || this == UNREACHABLE

    companion object {
        fun parse(s: String?): ErrorKind? = entries.firstOrNull { it.name == s }
    }
}

/** Everything the widget renders. */
data class WidgetState(
    val running: Int,
    val needsYou: Int,
    val topNeedsYouTitle: String?,
    val usage: List<UsageBar>,
    /** Epoch millis of the last successful fetch (0 = never). */
    val updatedAt: Long,
    /** Last error message, or null if the last refresh succeeded. */
    val error: String?,
    /** Thread to open from the "needs you" chip / row. */
    val topNeedsYouThreadId: String? = null,
    /** T3 environment the thread lives in (from /.well-known/t3/environment). */
    val environmentId: String? = null,
    val errorKind: ErrorKind? = null,
    /** "tunnel" / "LAN" / "demo": which endpoint produced this state. */
    val via: String? = null,
    /** True while the live-updates socket is connected. */
    val live: Boolean = false,
) {
    /** Same numbers and tap targets, ignoring timestamps and connection details. */
    fun sameContentAs(o: WidgetState): Boolean =
        running == o.running && needsYou == o.needsYou && topNeedsYouTitle == o.topNeedsYouTitle &&
            usage == o.usage && error == o.error && topNeedsYouThreadId == o.topNeedsYouThreadId &&
            environmentId == o.environmentId && errorKind == o.errorKind && live == o.live

    fun toJson(): String = JSONObject().apply {
        put("running", running)
        put("needsYou", needsYou)
        put("topNeedsYouTitle", topNeedsYouTitle ?: JSONObject.NULL)
        put("usage", JSONArray().apply {
            usage.forEach { bar ->
                put(JSONObject().apply {
                    put("label", bar.label)
                    // Existing caches store percent used under this key.
                    put("percent", bar.usedPercent)
                    put("urgency", bar.urgency)
                    bar.timing?.let {
                        put("resetsAt", it.resetsAt)
                        put("durationMs", it.durationMs)
                    }
                })
            }
        })
        put("updatedAt", updatedAt)
        put("error", error ?: JSONObject.NULL)
        put("topNeedsYouThreadId", topNeedsYouThreadId ?: JSONObject.NULL)
        put("environmentId", environmentId ?: JSONObject.NULL)
        put("errorKind", errorKind?.name ?: JSONObject.NULL)
        put("via", via ?: JSONObject.NULL)
        put("live", live)
    }.toString()

    companion object {
        val EMPTY = WidgetState(0, 0, null, emptyList(), 0L, null)

        fun fromJson(s: String?): WidgetState {
            if (s.isNullOrBlank()) return EMPTY
            return runCatching {
                val o = JSONObject(s)
                val arr = o.optJSONArray("usage") ?: JSONArray()
                WidgetState(
                    running = o.optInt("running"),
                    needsYou = o.optInt("needsYou"),
                    topNeedsYouTitle = o.optStringOrNull("topNeedsYouTitle"),
                    usage = (0 until arr.length()).map {
                        val b = arr.getJSONObject(it)
                        val used = b.getDouble("percent")
                        require(used.isFinite())
                        val durationMs = b.optLong("durationMs")
                        UsageBar(
                            label = b.getString("label"),
                            usedPercent = used,
                            urgency = b.optInt("urgency", used.toInt()),
                            timing = if (b.has("resetsAt") && durationMs > 0) {
                                UsageWindowTiming(b.getLong("resetsAt"), durationMs)
                            } else null,
                        )
                    },
                    updatedAt = o.optLong("updatedAt"),
                    error = o.optStringOrNull("error"),
                    topNeedsYouThreadId = o.optStringOrNull("topNeedsYouThreadId"),
                    environmentId = o.optStringOrNull("environmentId"),
                    errorKind = ErrorKind.parse(o.optStringOrNull("errorKind")),
                    via = o.optStringOrNull("via"),
                    live = o.optBoolean("live", false),
                )
            }.getOrDefault(EMPTY)
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }
    }
}

/** User settings, edited in [dev.local.t3widget.ui.SettingsActivity] or filled by a setup link. */
data class Settings(
    /** Primary base URL, normally the T3 Connect tunnel (https://….t3coderelay.com). */
    val serverUrl: String = "",
    /** Optional home-network base URL (http://192.168.x.y:3773). */
    val lanUrl: String = "",
    val token: String = "",
    val useFakeData: Boolean = true,
    /** Foreground service holding a live socket. */
    val liveUpdates: Boolean = true,
) {
    val configured: Boolean get() = token.isNotBlank() && (serverUrl.isNotBlank() || lanUrl.isNotBlank())
    /** True when the widget should talk to a real server. */
    val remote: Boolean get() = !useFakeData && configured
}
