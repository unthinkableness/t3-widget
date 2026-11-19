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
            when (t.optStringOrNull("activityRunStatus") ?: t.optString("status")) {
                "preparing", "starting", "running", "waiting" -> running++
                "completed" -> if (holdsCompletion(t)) running++
            }
        }
        // ISO-8601 timestamps sort lexically; newest wait first.
        val top = needing.maxByOrNull { it.since }
        return ThreadSummary(running, needing.size, top?.title, top?.id)
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
     *  - A window at 100% is exhausted: nothing to manage until it resets, so it's dropped.
     *    If every window of a provider is exhausted, the provider is hidden.
     *  - Of the remaining windows, the provider shows the one with the highest [urgency].
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
            ).filter { it.second.optDouble("usedPercent", 0.0) < EXHAUSTED_PERCENT }
            candidates
                .map { (label, w) ->
                    val used = w.optDouble("usedPercent", 0.0).coerceIn(0.0, 100.0)
                    UsageBar(label, used.toInt(), urgency(used, w, now))
                }
                .maxByOrNull { it.urgency }
                ?.let { bars += it }
        }
        return bars.sortedByDescending { it.urgency }
    }

    private const val EXHAUSTED_PERCENT = 99.5

    /**
     * Blend of current use and pace. `projected` = use extrapolated to the reset time; it's
     * weighted by how far through the window we are, so a fresh window isn't flagged off a
     * few minutes of data. Falls back to plain use if reset time or duration is unknown.
     */
    internal fun urgency(used: Double, w: JSONObject, now: Long): Int {
        val mins = w.optDouble("windowDurationMins", Double.NaN)
        val resetsAt = w.optStringOrNull("resetsAt")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        if (resetsAt == null || mins.isNaN() || mins <= 0) return used.toInt()
        val remaining = ((resetsAt - now) / (mins * 60_000)).coerceIn(0.0, 1.0)
        val elapsed = 1 - remaining
        val projected = (used / maxOf(elapsed, 0.25)).coerceAtMost(200.0)
        return (used * (1 - elapsed) + projected * elapsed).toInt()
    }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)
