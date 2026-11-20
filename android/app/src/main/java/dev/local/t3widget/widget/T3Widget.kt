package dev.local.t3widget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import android.content.Intent
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.action.ActionParameters
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.local.t3widget.T3Links
import dev.local.t3widget.data.ErrorKind
import dev.local.t3widget.data.Store
import dev.local.t3widget.data.UsageBar
import dev.local.t3widget.data.WidgetState
import dev.local.t3widget.live.LiveUpdateService
import dev.local.t3widget.ui.SettingsActivity
import dev.local.t3widget.update.UpdateState
import dev.local.t3widget.update.UpdateStore
import dev.local.t3widget.update.UpdateWorker
import dev.local.t3widget.update.Updater
import dev.local.t3widget.work.Refresher
import kotlin.math.roundToInt

// Palette from the approved mockup (card B).
private val CardBg = Color(0xFF1F1E21)
private val ChipBg = Color(0xFF2D2C30)
private val ChipNeedBg = Color(0xFF3A2F17)
private val TrackBg = Color(0xFF333236)
private val Fg = Color(0xFFF2F2F2)
private val Muted = Color(0xFF9A9AA0)
private val Blue = Color(0xFF4DA3FF)
private val Amber = Color(0xFFF5A524)
private val Red = Color(0xFFEF5350)

/** No successful refresh for this long = "stale". The live service also resyncs every 30 min. */
private const val STALE_MS = 40 * 60_000L

class T3Widget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 40.dp),  // narrow 3x1
            DpSize(250.dp, 40.dp),  // 4x1 (target)
            DpSize(250.dp, 110.dp), // 4x2 (resized taller)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = Store(context)
        val initialSettings = store.settings()
        val initial = store.state()
        val updates = UpdateStore(context)
        val initialUpdate = runCatching { updates.state() }.getOrNull() ?: UpdateState()
        val refresh = actionRunCallback<RefreshAction>()

        provideContent {
            val state by store.state.collectAsState(initial)
            val settings by store.settings.collectAsState(initialSettings)
            val update by updates.state.collectAsState(initialUpdate)
            val ctx = LocalContext.current
            val updatePending = Updater.showsWidgetIndicator(update)
            val actions = TapActions(
                // Card background: launch T3.
                openApp = actionStartActivity(T3Links.openApp(ctx, settings)),
                openRunning = actionStartActivity(T3Links.openThread(ctx, state.environmentId, state.runningThreadId, settings)),
                openUsage = actionStartActivity(T3Links.openUsage(ctx, settings)),
                // "Needs you" chip / row: the top needs-you thread, or T3 if there is none.
                openNeedsYou = actionStartActivity(
                    if (state.needsYou > 0) T3Links.openThread(ctx, state.environmentId, state.topNeedsYouThreadId, settings)
                    else T3Links.openApp(ctx, settings)
                ),
                refresh = refresh,
                // Only while an update waits for the user: the T3 tile opens the install confirmation.
                installUpdate = if (updatePending) actionStartActivity(
                    Intent(ctx, SettingsActivity::class.java).setAction(SettingsActivity.ACTION_INSTALL_UPDATE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ) else null,
            )
            WidgetContent(state, System.currentTimeMillis(), actions)
        }
    }
}

private class TapActions(
    val openApp: Action,
    val openRunning: Action,
    val openUsage: Action,
    val openNeedsYou: Action,
    val refresh: Action,
    val installUpdate: Action?,
)

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // A widget tap may start a foreground service from the background, so this also revives live updates.
        LiveUpdateService.startIfWanted(context)
        if (!LiveUpdateService.kick(context)) Refresher.refreshNow(context)
    }
}

class T3WidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = T3Widget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Refresher.schedulePeriodic(context)
        Refresher.refreshNow(context)
        LiveUpdateService.startIfWantedAsync(context)
        UpdateWorker.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Refresher.cancelAll(context)
        LiveUpdateService.stop(context)
    }
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

private fun cp(c: Color) = ColorProvider(c)

private fun providerColor(label: String) = when (label.substringBefore(' ')) {
    "Claude" -> Color(0xFFD97757)
    "Codex" -> Color(0xFF898990)
    "Gemini" -> Color(0xFF6C9BFF)
    else -> Muted
}

private fun ago(now: Long, then: Long): String {
    if (then <= 0) return "never"
    val m = (now - then).coerceAtLeast(0) / 60_000
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m"
        m < 48 * 60 -> "${m / 60}h"
        else -> "${m / (24 * 60)}d"
    }
}

private enum class Health { OK, STALE, OFFLINE, ERROR, EMPTY }

private fun health(s: WidgetState, now: Long) = when {
    s.error != null && s.errorKind?.soft == true -> Health.OFFLINE
    s.error != null -> Health.ERROR
    s.updatedAt == 0L -> Health.EMPTY
    now - s.updatedAt > STALE_MS -> Health.STALE
    else -> Health.OK
}

@Composable
private fun WidgetContent(s: WidgetState, now: Long, actions: TapActions) {
    val size = LocalSize.current
    val tall = size.height >= 100.dp
    val h = health(s, now)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(cp(CardBg))
            .cornerRadius(28.dp)
            .clickable(actions.openApp)
            .padding(horizontal = 16.dp, vertical = if (tall) 14.dp else 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (tall) TallLayout(s, now, h, actions) else CompactLayout(s, now, h, actions, narrow = size.width < 230.dp)
    }
}

/** 4x1: logo · stacked chips · two tightest usage bars. */
@Composable
private fun CompactLayout(s: WidgetState, now: Long, h: Health, actions: TapActions, narrow: Boolean) {
    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Logo(h, actions.installUpdate)
        Spacer(GlanceModifier.width(10.dp))
        Column {
            Chip(Blue, "${s.running} running", highlight = false, onClick = actions.openRunning)
            Spacer(GlanceModifier.height(4.dp))
            Chip(Amber, "${s.needsYou} needs you", highlight = s.needsYou > 0, onClick = actions.openNeedsYou)
        }
        Spacer(GlanceModifier.width(12.dp))
        Column(
            modifier = GlanceModifier.defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusLine = statusText(h, s, now)
            val maxBars = if (statusLine != null || narrow) 1 else 2
            if (statusLine != null) {
                SmallText(statusLine, if (h == Health.ERROR) Red else Muted, maxLines = 1, onClick = actions.refresh)
                Spacer(GlanceModifier.height(3.dp))
            }
            UsageArea(tightest(s.usage, maxBars), now, h.dim, actions.openUsage)
        }
    }
}

/** 4x2: header row, the thread that needs you, all usage bars. */
@Composable
private fun TallLayout(s: WidgetState, now: Long, h: Health, actions: TapActions) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Logo(h, actions.installUpdate)
            Spacer(GlanceModifier.width(10.dp))
            Chip(Blue, "${s.running} running", highlight = false, onClick = actions.openRunning)
            Spacer(GlanceModifier.width(6.dp))
            Chip(Amber, "${s.needsYou} needs you", highlight = s.needsYou > 0, onClick = actions.openNeedsYou)
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = (if (s.live && h == Health.OK) "● " else "↻ ") + ago(now, s.updatedAt),
                modifier = GlanceModifier.clickable(actions.refresh).padding(4.dp),
                style = TextStyle(color = cp(if (h == Health.ERROR) Red else Muted), fontSize = 11.sp),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(10.dp))
        val title = s.topNeedsYouTitle
        if (title != null && s.needsYou > 0) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().background(cp(ChipNeedBg)).cornerRadius(14.dp)
                    .clickable(actions.openNeedsYou)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(Amber)
                Spacer(GlanceModifier.width(8.dp))
                Text(title, style = TextStyle(color = cp(Fg), fontSize = 13.sp), maxLines = 1)
            }
            Spacer(GlanceModifier.height(10.dp))
        }
        statusText(h, s, now)?.let {
            SmallText(it, if (h == Health.ERROR) Red else Muted, maxLines = 1, onClick = actions.refresh)
            Spacer(GlanceModifier.height(4.dp))
        }
        UsageArea(s.usage.take(4), now, h.dim, actions.openUsage)
    }
}

private val Health.dim get() = this == Health.ERROR || this == Health.STALE || this == Health.OFFLINE

private fun statusText(h: Health, s: WidgetState, now: Long): String? {
    val age = if (s.updatedAt > 0) " · ${ago(now, s.updatedAt)}" else ""
    return when (h) {
        Health.OFFLINE -> (if (s.errorKind == ErrorKind.OFFLINE) "Offline" else "PC unreachable") + age
        Health.ERROR -> "⚠ " + errorLabel(s) + age
        Health.STALE -> "Stale · ${ago(now, s.updatedAt)} · tap to refresh"
        Health.EMPTY -> "Tap to refresh"
        Health.OK -> null
    }
}

private fun errorLabel(s: WidgetState): String = when (s.errorKind) {
    ErrorKind.AUTH -> "Re-pair needed"
    ErrorKind.SCOPE -> "Token lacks access"
    ErrorKind.PROTOCOL -> "T3 updated; update widget"
    ErrorKind.NOT_SET_UP -> "Not set up · open settings"
    else -> s.error ?: "error"
}

private fun tightest(usage: List<UsageBar>, n: Int) = usage.sortedByDescending { it.urgency }.take(n)

@Composable
private fun UsageArea(bars: List<UsageBar>, now: Long, dim: Boolean, onClick: Action) {
    Column(modifier = GlanceModifier.fillMaxWidth().clickable(onClick)) {
        if (bars.isEmpty()) SmallText("Usage", Muted, maxLines = 1)
        else UsageBars(bars, now, dim)
    }
}

@Composable
private fun UsageBars(bars: List<UsageBar>, now: Long, dim: Boolean) {
    bars.forEachIndexed { i, b ->
        if (i > 0) Spacer(GlanceModifier.height(4.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                b.label,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = cp(if (dim) Muted else Fg), fontSize = 11.sp),
                maxLines = 1,
            )
            Text("${b.percentLeft}% left", style = TextStyle(color = cp(Muted), fontSize = 10.sp), maxLines = 1)
        }
        Spacer(GlanceModifier.height(2.dp))
        val fill = providerColor(b.label)
        val barSize = GlanceModifier.fillMaxWidth().height(5.dp)
        Box(modifier = barSize) {
            LinearProgressIndicator(
                progress = b.remainingFraction,
                modifier = barSize.cornerRadius(3.dp),
                color = cp(if (dim) fill.copy(alpha = 0.45f) else fill),
                backgroundColor = cp(TrackBg),
            )
            b.timing?.let { timing ->
                val pace = timing.evenPaceRemaining(now)
                Image(
                    provider = ImageProvider(paceMarker(pace, dim)),
                    contentDescription = "Even pace: ${(pace * 100).roundToInt()}% left",
                    modifier = barSize,
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }
}

/** Glance scales this transparent overlay to the track width chosen by the launcher. */
private fun paceMarker(pace: Double, dim: Boolean): Bitmap {
    val width = 320
    val height = 10
    val inset = 5f
    val x = inset + (width - 2 * inset) * pace.toFloat()
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = CardBg.toArgb()
        canvas.drawRect(x - 4f, 0f, x + 4f, height.toFloat(), paint)
        paint.color = if (dim) Muted.toArgb() else Color.White.toArgb()
        canvas.drawRect(x - 2f, 0f, x + 2f, height.toFloat(), paint)
    }
}

@Composable
private fun Logo(h: Health, installUpdate: Action?) {
    // Outer box lets a small status dot sit on the logo's top-right corner (and the update dot bottom-right).
    val base = GlanceModifier.size(36.dp)
    Box(modifier = if (installUpdate != null) base.clickable(installUpdate) else base, contentAlignment = Alignment.TopEnd) {
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = GlanceModifier.size(32.dp).background(cp(Color.Black)).cornerRadius(9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("T3", style = TextStyle(color = cp(Fg), fontSize = 12.sp, fontWeight = FontWeight.Bold))
            }
        }
        when (h) {
            Health.ERROR -> Dot(Red, 9)
            Health.STALE, Health.OFFLINE -> Dot(Muted, 9)
            else -> {}
        }
        if (installUpdate != null) {
            // App update downloaded and waiting for the user's confirmation.
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                Dot(Blue, 8)
            }
        }
    }
}

@Composable
private fun Chip(dot: Color, label: String, highlight: Boolean, onClick: Action? = null) {
    val base = GlanceModifier.background(cp(if (highlight) ChipNeedBg else ChipBg))
    Row(
        modifier = (if (onClick != null) base.clickable(onClick) else base)
            .cornerRadius(12.dp)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(dot)
        Spacer(GlanceModifier.width(5.dp))
        Text(label, style = TextStyle(color = cp(Fg), fontSize = 12.sp), maxLines = 1)
    }
}

@Composable
private fun Dot(c: Color, sizeDp: Int = 7) {
    Box(modifier = GlanceModifier.size(sizeDp.dp).background(cp(c)).cornerRadius((sizeDp / 2f + 0.5f).dp)) {}
}

@Composable
private fun SmallText(text: String, color: Color, maxLines: Int, onClick: Action? = null) {
    val modifier = if (onClick != null) GlanceModifier.clickable(onClick) else GlanceModifier
    Text(text, modifier = modifier, style = TextStyle(color = cp(color), fontSize = 10.sp), maxLines = maxLines)
}
