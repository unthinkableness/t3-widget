package dev.local.t3widget

import dev.local.t3widget.data.ErrorKind
import dev.local.t3widget.data.ShellModel
import dev.local.t3widget.data.ThreadMapping
import dev.local.t3widget.data.UsageBar
import dev.local.t3widget.data.UsageWindowTiming
import dev.local.t3widget.data.WidgetState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun fixture(name: String): JSONObject =
    JSONObject(ThreadMappingTest::class.java.classLoader!!.getResource(name)!!.readText())

class ThreadMappingTest {

    // Expected values cross-checked with api/probe.mjs summarizeThreads() on the same fixture:
    // running 4, needsYou 3 (t-parent via subagent, t-input, t-approval), top = t-parent.
    @Test fun summarizesFixtureLikeT3() {
        val s = ThreadMapping.summarize(fixture("shell_fixture.json"))
        assertEquals(4, s.running) // t-run, t-coord (awaits subagent), t-auth (auth_refresh ignored), t-monitor
        assertEquals(3, s.needsYou)
        assertEquals("t-parent", s.topThreadId) // newest wait (12:00) rolled up from its subagent
        assertEquals("Widget · Parent thread", s.topTitle)
    }

    @Test fun noProjectTitleIsDropped() {
        val shell = fixture("shell_fixture.json")
        // Keep only t-input (project "No project").
        val threads = shell.getJSONArray("threads")
        val only = (0 until threads.length()).map { threads.getJSONObject(it) }.filter { it.getString("id") == "t-input" }
        val s = ThreadMapping.summarize(only, ThreadMapping.projectTitles(shell.getJSONArray("projects")))
        assertEquals(1, s.needsYou)
        assertEquals("Question for you", s.topTitle)
        assertEquals("t-input", s.topThreadId)
    }

    @Test fun emptyShell() {
        val s = ThreadMapping.summarize(JSONObject("""{"threads":[],"projects":[]}"""))
        assertEquals(0, s.running)
        assertEquals(0, s.needsYou)
        assertNull(s.topThreadId)
    }

    @Test fun usageBarsOnePerProviderMostUrgentFirst() {
        val bars = ThreadMapping.usageBars(fixture("config_fixture.json"))
        assertEquals(
            listOf(UsageBar("Codex 5h", 91.0), UsageBar("Claude wk", 59.0), UsageBar("Gemini wk", 20.0)),
            bars,
        )
    }

    private fun limits(vararg windows: String) = JSONObject(
        """{"providers":[{"instanceId":"antigravity","enabled":true,"usageLimits":{"windows":[${windows.joinToString(",")}]}}]}"""
    )

    @Test fun showsWeeklyAtOnePercentLeftWhenSessionIsFull() {
        val bars = ThreadMapping.usageBars(limits(
            """{"id":"gemini-5h","kind":"session","usedPercent":0}""",
            """{"id":"gemini-weekly","kind":"weekly","usedPercent":99}""",
        ))
        val bar = bars.single()
        assertEquals("Gemini wk", bar.label)
        assertEquals(1, bar.percentLeft)
        assertEquals(0.01f, bar.remainingFraction, 0.0001f)
    }

    @Test fun providerWithFullAllowanceIsHidden() {
        val bars = ThreadMapping.usageBars(limits(
            """{"id":"gemini-5h","kind":"session","usedPercent":0}""",
            """{"id":"gemini-weekly","kind":"weekly","usedPercent":0}""",
        ))
        assertEquals(emptyList<UsageBar>(), bars)
    }

    @Test fun exhaustedWindowIsVisibleAndTakesPriority() {
        val bars = ThreadMapping.usageBars(limits(
            """{"id":"gemini-5h","kind":"session","usedPercent":100}""",
            """{"id":"gemini-weekly","kind":"weekly","usedPercent":99}""",
        ))
        assertEquals("Gemini 5h", bars.single().label)
        assertEquals(0, bars.single().percentLeft)
    }

    @Test fun fastBurnBeatsHigherButSlowWindow() {
        val now = Instant.parse("2026-11-20T12:00:00Z").toEpochMilli()
        // Weekly: 60% used, 1 day into 7 → on pace for way over 100%.
        // 5h: 70% used, 4.5h into 5h → will end around 78%.
        val bars = ThreadMapping.usageBars(limits(
            """{"id":"gemini-5h","kind":"session","usedPercent":70,"windowDurationMins":300,"resetsAt":"2026-11-20T12:30:00Z"}""",
            """{"id":"gemini-weekly","kind":"weekly","usedPercent":60,"windowDurationMins":10080,"resetsAt":"2026-11-26T12:00:00Z"}""",
        ), now)
        assertEquals("Gemini wk", bars.single().label)
        assertEquals(40, bars.single().percentLeft)
        assert(bars.single().urgency > 70)
    }

    @Test fun evenPaceTracksTimeLeftInTheSelectedWindow() {
        val resetAt = Instant.parse("2026-11-20T12:00:00Z").toEpochMilli()
        val hour = 60 * 60_000L
        val bar = ThreadMapping.usageBars(limits(
            """{"id":"gemini-5h","kind":"session","usedPercent":12.7,"windowDurationMins":300,"resetsAt":"2026-11-20T12:00:00Z"}""",
        ), resetAt - 2 * hour).single()
        assertEquals(87, bar.percentLeft)
        assertEquals(0.873f, bar.remainingFraction, 0.0001f)
        val timing = checkNotNull(bar.timing)
        assertEquals(1.0, timing.evenPaceRemaining(resetAt - 6 * hour), 0.0)
        assertEquals(0.4, timing.evenPaceRemaining(resetAt - 2 * hour), 0.0001)
        assertEquals(0.2, timing.evenPaceRemaining(resetAt - hour), 0.0001)
        assertEquals(0.0, timing.evenPaceRemaining(resetAt + hour), 0.0)

        val invalidTiming = ThreadMapping.usageBars(limits(
            """{"id":"gemini-5h","kind":"session","usedPercent":12.7,"windowDurationMins":300,"resetsAt":"bad date"}""",
        ), resetAt - 2 * hour).single()
        assertNull(invalidTiming.timing)
    }

    @Test fun shellModelAppliesDeltas() {
        val m = ShellModel()
        m.reset(fixture("shell_fixture.json"))
        assertEquals(730760, m.sequence)
        assertEquals(3, m.summary().needsYou)

        // t-input answered: request cleared, now running.
        val updated = JSONObject(
            """{"kind":"thread.updated","sequence":730770,"location":"active","thread":
              {"id":"t-input","projectId":"p-none","title":"Question for you","lineage":{"rootThreadId":"t-input"},
               "status":"running","activityRunStatus":"running","pendingRuntimeRequest":null,"pendingBackgroundTasks":[],
               "archivedAt":null,"deletedAt":null}}"""
        )
        assertEquals(ShellModel.Applied.Changed, m.apply(updated))
        assertEquals(730770, m.sequence)
        assertEquals(2, m.summary().needsYou)
        assertEquals(5, m.summary().running)

        // t-approval removed (deleted), t-sub archived → parent no longer needs you.
        m.apply(JSONObject("""{"kind":"thread.removed","sequence":730771,"location":"active","threadId":"t-approval"}"""))
        m.apply(JSONObject("""{"kind":"thread.updated","sequence":730772,"location":"archive","thread":{"id":"t-sub","archivedAt":"x"}}"""))
        val s = m.summary()
        assertEquals(0, s.needsYou)
        assertNull(s.topThreadId)
        assertEquals(6, s.running) // t-parent now counts as running

        // Project metadata refresh keeps threads.
        m.apply(JSONObject("""{"kind":"snapshot","snapshot":{"snapshotSequence":1,"projects":[{"id":"p-widget","title":"Renamed"}],"threads":[]},"resolvedRepositoryIdentityRoots":["C:/dev/widget"]}"""))
        assertEquals(6, m.summary().running)
        assertEquals(730772, m.sequence)

        assertEquals(ShellModel.Applied.SnapshotRequired, m.apply(JSONObject("""{"kind":"snapshot-required"}""")))
        assertEquals(ShellModel.Applied.Ignored, m.apply(JSONObject("""{"kind":"synchronized"}""")))
    }

    @Test fun widgetStateJsonRoundTrip() {
        val s = WidgetState(
            running = 2, needsYou = 1, topNeedsYouTitle = "Widget · X",
            usage = listOf(UsageBar("Claude 5h", 40.5, 56, UsageWindowTiming(18_000_000L, 18_000_000L))),
            updatedAt = 123, error = "Offline", topNeedsYouThreadId = "thread:abc", environmentId = "env-1",
            errorKind = ErrorKind.OFFLINE, via = "LAN", live = true,
        )
        assertEquals(s, WidgetState.fromJson(s.toJson()))
        // Old (v0.1) JSON still loads.
        val old = WidgetState.fromJson("""{"running":1,"needsYou":0,"topNeedsYouTitle":null,"usage":[{"label":"Claude 5h","percent":40}],"updatedAt":5,"error":null}""")
        assertEquals(1, old.running)
        assertNull(old.errorKind)
        assertEquals(60, old.usage.single().percentLeft)
        assertNull(old.usage.single().timing)
        assertTrue(ErrorKind.OFFLINE.soft)
    }
}
