package dev.local.t3widget.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.local.t3widget.R
import dev.local.t3widget.T3Links
import dev.local.t3widget.data.Store
import dev.local.t3widget.data.WidgetState
import dev.local.t3widget.update.UpdateWorker
import dev.local.t3widget.update.Updater
import dev.local.t3widget.widget.T3WidgetReceiver
import dev.local.t3widget.work.Refresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * "Live updates" foreground service (type specialUse; dataSync has a daily cap on Android 15+).
 * Holds one [LiveSession] socket while at least one widget exists and the toggle is on.
 * Also listens for network changes, unlock (USER_PRESENT) and screen on/off while it runs.
 */
class LiveUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var session: LiveSession
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(WidgetState.EMPTY)
        instance = this
        session = LiveSession(applicationContext).apply {
            screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
            onConnectedChanged = { connected ->
                isConnected = connected
            }
        }
        registerSignals()
        scope.launch {
            try {
                session.run()
            } finally {
                isConnected = false
            }
            // run() returned: live updates no longer wanted.
            Log.i(TAG, "live session finished, stopping service")
            stopSelf()
        }
        // Restart the session loop's wait when settings change (new token, new URL, toggle off).
        scope.launch {
            Store(applicationContext).settings.drop(1).collect { s ->
                if (!s.remote || !s.liveUpdates) stopSelf() else session.kick(LiveSession.Kick.SETTINGS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground promptly after every startForegroundService().
        scope.launch { goForeground(Store(applicationContext).state()) }
        if (intent?.action == ACTION_REFRESH) session.kick(LiveSession.Kick.MANUAL)
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        isConnected = false
        unregisterSignals()
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(state: WidgetState) {
        val n = buildNotification(this, state)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException etc. Fall back to WorkManager only.
            Log.w(TAG, "startForeground failed: $e")
            stopSelf()
        }
    }

    private fun registerSignals() {
        val cm = getSystemService(ConnectivityManager::class.java)
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = session.kick(LiveSession.Kick.NETWORK)
            override fun onLost(network: Network) = session.kick(LiveSession.Kick.NETWORK)
        }.also { runCatching { cm?.registerDefaultNetworkCallback(it) } }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> session.kick(LiveSession.Kick.USER_PRESENT)
                    Intent.ACTION_SCREEN_ON -> session.screenOn = true
                    Intent.ACTION_SCREEN_OFF -> session.screenOn = false
                }
            }
        }.also {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(this, it, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun unregisterSignals() {
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        receiver?.let { runCatching { unregisterReceiver(it) } }
        netCallback = null
        receiver = null
    }

    companion object {
        private const val TAG = "T3Live"
        const val CHANNEL_ID = "live_updates"
        const val NOTIFICATION_ID = 31
        const val ACTION_REFRESH = "dev.local.t3widget.action.REFRESH"

        /** True while the socket is connected (process-wide; false after a process restart). */
        @Volatile var isConnected: Boolean = false
            private set

        @Volatile private var instance: LiveUpdateService? = null

        val isRunning: Boolean get() = instance != null

        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun widgetCount(context: Context): Int = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, T3WidgetReceiver::class.java)).size

        suspend fun wanted(context: Context): Boolean {
            val s = Store(context).settings()
            return s.remote && s.liveUpdates && widgetCount(context) > 0
        }

        /** Start or stop the service to match settings and widget count. */
        suspend fun startIfWanted(context: Context, fromBackground: Boolean = false) {
            val ctx = context.applicationContext
            if (!wanted(ctx)) {
                stop(ctx)
                return
            }
            if (isRunning) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LiveUpdateService::class.java))
            } catch (e: Exception) {
                // Android 12+ blocks background FGS starts unless exempt (battery optimisation off, boot, widget tap).
                Log.w(TAG, "can't start live service (background=$fromBackground): $e")
            }
        }

        /** Fire-and-forget version for receivers and UI callbacks. */
        fun startIfWantedAsync(context: Context) {
            val ctx = context.applicationContext
            appScope.launch { startIfWanted(ctx) }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, LiveUpdateService::class.java))
        }

        /** Manual refresh while running: HTTP resync + usage on the live socket. */
        fun kick(context: Context): Boolean {
            val svc = instance ?: return false
            svc.session.kick(LiveSession.Kick.MANUAL)
            return true
        }

        /** Re-render the ongoing notification from the stored state (no-op when not running). */
        suspend fun updateNotification(context: Context) {
            val svc = instance ?: return
            val n = buildNotification(svc, Store(context).state())
            runCatching { svc.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n) }
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "T3 widget live updates", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps the T3 widget connected so it updates within seconds."
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }

        fun notificationText(s: WidgetState): String = when {
            s.updatedAt == 0L && s.error == null -> "Connecting to T3…"
            s.errorKind?.soft == true -> "${s.running} running · ${s.needsYou} needs you · reconnecting"
            s.error != null -> s.error
            else -> "${s.running} running · ${s.needsYou} needs you"
        }

        private fun buildNotification(context: Context, s: WidgetState): Notification {
            ensureChannel(context)
            val tap = PendingIntent.getActivity(
                context, 0, T3Links.openApp(context, null),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_t3)
                .setContentTitle(notificationText(s))
                .setContentIntent(tap)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}

/**
 * BOOT_COMPLETED / app update (including a self-update): report the update result, reschedule the update check
 * and fallback jobs, restart live updates and redraw the widgets.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val replaced = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
                runCatching { UpdateWorker.schedulePeriodic(context) }
                val hasWidgets = LiveUpdateService.widgetCount(context) > 0
                if (hasWidgets) Refresher.schedulePeriodic(context)
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        // Updater errors must never stop the widget/live service from coming back.
                        if (replaced) Updater.reconcile(context)
                        if (hasWidgets) {
                            LiveUpdateService.startIfWanted(context, fromBackground = true)
                            if (!LiveUpdateService.isRunning) Refresher.refreshNow(context)
                            if (replaced) runCatching { Refresher.redraw(context) }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG_BOOT, "boot/update handling failed: $t")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG_BOOT = "T3Live"
    }
}
