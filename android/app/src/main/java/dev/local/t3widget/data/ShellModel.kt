package dev.local.t3widget.data

import org.json.JSONObject

/**
 * In-memory copy of the T3 shell (threads + project titles), kept current by applying
 * `orchestration.subscribeShell` stream items. Not thread-safe: the live session mutates it from one coroutine.
 *
 * Stream item kinds (server v0.1.10, `OrchestrationV2ShellStreamItem`):
 *  - `thread.updated` {sequence, location: active|archive, thread}  → upsert (archive → drop)
 *  - `thread.removed` {sequence, location, threadId}                → drop
 *  - `project.updated` {sequence, project} / `project.removed` {sequence, projectId}
 *  - `snapshot` {snapshot, resolvedRepositoryIdentityRoots?}: with identity roots it only refreshes
 *    project metadata (threads is empty); without, it is an authoritative full snapshot.
 *  - `snapshot-required`: the server can't replay from our cursor → caller re-fetches over HTTP.
 *  - `synchronized`: marker, ignored.
 */
class ShellModel {
    private val threads = LinkedHashMap<String, JSONObject>()
    private val projects = HashMap<String, String>()

    /** Highest application-event sequence seen; the resume cursor for `afterSequence`. */
    var sequence: Long = -1
        private set

    val threadCount: Int get() = threads.size

    /** Replace everything with an HTTP `/api/orchestration/shell` snapshot. */
    fun reset(snapshot: JSONObject) {
        threads.clear()
        projects.clear()
        snapshot.optJSONArray("threads").objects().forEach { threads[it.optString("id")] = it }
        projects.putAll(ThreadMapping.projectTitles(snapshot.optJSONArray("projects")))
        sequence = snapshot.optLong("snapshotSequence", -1)
    }

    sealed interface Applied {
        /** The item may have changed the summary. */
        data object Changed : Applied
        data object Ignored : Applied
        /** Re-fetch the HTTP snapshot and resubscribe. */
        data object SnapshotRequired : Applied
    }

    fun apply(item: JSONObject): Applied {
        val seq = item.optLong("sequence", -1)
        if (seq > sequence) sequence = seq
        return when (item.optString("kind")) {
            "thread.updated" -> {
                val t = item.optJSONObject("thread") ?: return Applied.Ignored
                val id = t.optString("id")
                if (item.optString("location") == "archive" || !t.isNull("archivedAt")) threads.remove(id)
                else threads[id] = t
                Applied.Changed
            }
            "thread.removed" -> {
                threads.remove(item.optString("threadId"))
                Applied.Changed
            }
            "project.updated" -> {
                val p = item.optJSONObject("project") ?: return Applied.Ignored
                projects[p.optString("id")] = p.optString("title")
                Applied.Changed
            }
            "project.removed" -> {
                projects.remove(item.optString("projectId"))
                Applied.Changed
            }
            "snapshot" -> {
                val snap = item.optJSONObject("snapshot") ?: return Applied.Ignored
                if (item.has("resolvedRepositoryIdentityRoots")) {
                    // Metadata refresh: projects only, threads is always empty here.
                    projects.putAll(ThreadMapping.projectTitles(snap.optJSONArray("projects")))
                } else {
                    reset(snap)
                }
                Applied.Changed
            }
            "snapshot-required" -> Applied.SnapshotRequired
            else -> Applied.Ignored
        }
    }

    fun summary(): ThreadSummary = ThreadMapping.summarize(threads.values, projects)
}
