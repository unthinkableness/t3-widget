package dev.local.t3widget.data

import kotlinx.coroutines.delay
import kotlin.math.sin

/** Plausible, slowly drifting demo data so the widget visibly changes on refresh. */
class FakeT3Repository(private val now: () -> Long = System::currentTimeMillis) : T3Repository {

    override suspend fun fetch(previous: WidgetState): WidgetState {
        delay(300) // pretend to hit the network
        val t = now()
        val minute = t / 60_000
        val running = (minute % 4).toInt()            // 0..3
        val needsYou = ((minute / 3) % 3).toInt()     // 0..2
        fun drift(base: Int, amp: Int, period: Double) =
            (base + amp * sin(minute / period)).toInt().coerceIn(0, 100)

        return WidgetState(
            running = running,
            needsYou = needsYou,
            topNeedsYouTitle = if (needsYou > 0) TITLES[(minute % TITLES.size).toInt()] else null,
            usage = listOf(
                UsageBar("Claude 5h", drift(62, 25, 7.0)),
                UsageBar("Claude wk", drift(41, 10, 40.0)),
                UsageBar("Gemini", drift(20, 12, 13.0)),
                UsageBar("Codex", drift(80, 15, 9.0)),
            ),
            updatedAt = t,
            error = null,
            topNeedsYouThreadId = if (needsYou > 0) "demo-thread" else null,
            environmentId = null,
            via = "demo",
        )
    }

    private companion object {
        val TITLES = listOf(
            "Review auth PR: approve edit?",
            "Widget build: allow sdkmanager?",
            "Blight Hunters · NPC dialogue question",
        )
    }
}
