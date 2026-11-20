package dev.local.t3widget.data

import org.json.JSONArray
import org.json.JSONObject

/** Result of mapping a shell snapshot to widget buckets. */
data class ThreadSummary(
    val running: Int,
    val needsYou: Int,
    /** "Project · Title" of the thread that most recently started waiting on the user. */
    val topTitle: String?,
    val topThreadId: String?,
    val runningThreadId: String?,
)

/**
 * Pure mapping from T3 shell data to widget numbers. Mirrors T3's own agentAwareness logic
 * (packages/shared/src/agentAwareness.ts, same as api/probe.mjs and API.md §3.2), so the widget agrees
 * with the T3 app's notifications.
 */
object ThreadMapping {

    fun summarize(shell: JSONObject): ThreadSummary =
        summarize(shell.optJSONArray("threads").objects(), projectTitles(shell.optJSONArray("projects")))

    fun projectTitles(projects: JSONArray?): Map<String, String> =
        projects.objects().associate { it.optString("id") to it.optString("title") }

    fun summarize(threads: Collection<JSONObject>, projects: Map<String, String>): ThreadSummary {
        val all = threads.filter { it.isNull("archivedAt") && it.isNull("deletedAt") }
        val byId = all.associateBy { it.optString("id") }

        fun listedAncestor(t: JSONObject): JSONObject? {
            var cur: JSONObject? = t
            var i = 0
            while (cur != null && !cur.isListed() && i++ < 50) {
                cur = cur.lineage().optStringOrNull("parentThreadId")?.let { byId[it] }
            }
            return cur?.takeIf { it.isListed() } ?: t.lineage().optStringOrNull("rootThreadId")?.let { byId[it] }
        }

        // listed threadId -> createdAt of the first subagent request that rolls up to it
        val subagentWaits = HashMap<String, String>()
        for (t in all) {
            if (!subagentAwaitsUser(t)) continue
            val top = listedAncestor(t) ?: continue
            subagentWaits.putIfAbsent(top.optString("id"), t.request()!!.optString("createdAt"))
        }

        var running = 0
        var runningThreadId: String? = null
        var runningUpdatedAt = ""
        data class Need(val since: String, val title: String, val id: String)
        val needing = ArrayList<Need>()
        for (t in all.filter { it.isListed() }) {
            val req = t.request()?.takeIf { it.optString("kind") != "auth_refresh" }
            val subWait = subagentWaits[t.optString("id")]
            if (req != null || subWait != null) {
                val title = t.optString("title").ifBlank { "Untitled thread" }
                val project = projects[t.optString("projectId")]?.takeIf { it.isNotBlank() && it != "No project" }
                needing += Need(
                    since = req?.optString("createdAt") ?: subWait ?: t.optString("updatedAt"),
                    title = if (project != null) "$project · $title" else title,
                    id = t.optString("id"),
                )
                continue
            }
            val isRunning = when (t.optStringOrNull("activityRunStatus") ?: t.optString("status")) {
                "preparing", "starting", "running", "waiting" -> true
                "completed" -> holdsCompletion(t)
                else -> false
            }
            if (isRunning) {
                running++
                val id = t.optString("id")
                val updatedAt = t.optString("updatedAt")
                if (id.isNotBlank() && (runningThreadId == null || updatedAt > runningUpdatedAt)) {
                    runningThreadId = id
                    runningUpdatedAt = updatedAt
                }
            }
        }
        // ISO-8601 timestamps sort lexically; newest wait first.
        val top = needing.maxByOrNull { it.since }
        return ThreadSummary(running, needing.size, top?.title, top?.id, runningThreadId)
    }

    private fun JSONObject.lineage() = optJSONObject("lineage") ?: JSONObject()
    private fun JSONObject.request() = optJSONObject("pendingRuntimeRequest")
    private fun JSONObject.isListed() =
        lineage().optStringOrNull("relationshipToParent") != "subagent" && !lineage().optBoolean("sideChat", false)

    private fun subagentAwaitsUser(t: JSONObject): Boolean {
        val req = t.request() ?: return false
        val kind = req.optString("kind")
        return kind != "auth_refresh" &&
            (kind != "user_input" || req.optBoolean("userOnly", false)) &&
            t.lineage().optStringOrNull("relationshipToParent") == "subagent" &&
            t.optString("creationSource") != "provider" &&
            !t.isNull("activityRunStatus") && t.has("activityRunStatus")
    }

    private fun holdsCompletion(t: JSONObject): Boolean {
        val tasks = t.optJSONArray("pendingBackgroundTasks").objects()
        if (tasks.any { it.optBoolean("awaited") || it.optString("kind") in setOf("subagent", "background_task") }) return true
        return t.optBoolean("latestRunBackgroundWake") && tasks.any {
            it.optString("kind") == "monitor" && !it.optString("taskId").startsWith("pull-request-watch:")
        }
    }

    // ------------------------------------------------------------------ usage limits

    private val PROVIDER_NAMES = mapOf("claudeAgent" to "Claude", "codex" to "Codex", "antigravity" to "Gemini")

    /**
     * `config.providers[].usageLimits` → one bar per provider, most urgent first.
     *
     *  - Only the main 5h (session) and weekly windows count; `3p-*` windows are skipped.
     *  - Windows at 100% left are hidden. Exhausted windows get highest priority.
     *  - Of the visible windows, the provider shows the one with the highest urgency.
     */
    fun usageBars(config: JSONObject, now: Long = System.currentTimeMillis()): List<UsageBar> {
        val bars = ArrayList<UsageBar>()
        for (p in config.optJSONArray("providers").objects()) {
            val name = PROVIDER_NAMES[p.optString("instanceId")] ?: continue
            if (!p.optBoolean("enabled")) continue
            val windows = p.optJSONObject("usageLimits")?.optJSONArray("windows").objects()
                .filterNot { it.optString("id").startsWith("3p-") }
            val candidates = listOfNotNull(
                windows.firstOrNull { it.optString("kind") == "session" }?.let { "$name 5h" to it },
                windows.firstOrNull { it.optString("kind") == "weekly" }?.let { "$name wk" to it },
            )
            candidates
                .mapNotNull { (label, w) ->
                    val percent = w.optDouble("usedPercent")
                    if (!percent.isFinite()) return@mapNotNull null
                    val used = percent.coerceIn(0.0, 100.0)
                    val timing = windowTiming(w)
                    UsageBar(label, used, urgency(used, timing, now), timing)
                }
                .filter { it.percentLeft < 100 }
                .maxByOrNull { it.urgency }
                ?.let { bars += it }
        }
        return bars.sortedByDescending { it.urgency }
    }

    private fun windowTiming(w: JSONObject): UsageWindowTiming? {
        val mins = w.optDouble("windowDurationMins", Double.NaN)
        if (!mins.isFinite() || mins <= 0) return null
        val durationMs = (mins * 60_000).toLong()
        if (durationMs <= 0) return null
        val reset = w.optStringOrNull("resetsAt") ?: return null
        val resetsAt = runCatching { java.time.Instant.parse(reset).toEpochMilli() }.getOrNull() ?: return null
        return UsageWindowTiming(resetsAt, durationMs)
    }

    /**
     * Blend of current use and pace. `projected` = use extrapolated to the reset time; it's
     * weighted by how far through the window we are, so a fresh window isn't flagged off a
     * few minutes of data. Falls back to plain use if reset time or duration is unknown.
     */
    private fun urgency(used: Double, timing: UsageWindowTiming?, now: Long): Int {
        if (used >= 100.0) return Int.MAX_VALUE
        if (timing == null) return used.toInt()
        val elapsed = 1 - timing.evenPaceRemaining(now)
        val projected = (used / maxOf(elapsed, 0.25)).coerceAtMost(200.0)
        return (used * (1 - elapsed) + projected * elapsed).toInt()
    }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)
