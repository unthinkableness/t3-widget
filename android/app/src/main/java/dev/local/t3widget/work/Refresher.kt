package dev.local.t3widget.work

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.local.t3widget.data.ApiException
import dev.local.t3widget.data.ErrorKind
import dev.local.t3widget.data.Store
import dev.local.t3widget.data.T3Repository
import dev.local.t3widget.data.WidgetState
import dev.local.t3widget.data.currentNetClass
import dev.local.t3widget.data.NetClass
import dev.local.t3widget.live.LiveUpdateService
import dev.local.t3widget.widget.T3Widget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.TimeUnit

object Refresher {
    private const val TAG = "T3Widget"
    private const val PERIODIC = "t3widget-periodic"
    private const val ONE_SHOT = "t3widget-now"
    private const val ON_NETWORK = "t3widget-on-network"

    /** Delays before attempts 2 and 3 for transient failures. */
    private val BACKOFF_MS = longArrayOf(2_000, 6_000)

    /**
     * Fetch once with retry/backoff, persist the result (or the error, keeping old numbers), redraw widgets.
     * Used by WorkManager, the settings screen and the live service's resyncs.
     */
    suspend fun refresh(context: Context): WidgetState {
        val store = Store(context)
        val settings = store.settings()
        var attempt = 0
        while (true) {
            val previous = store.state()
            try {
                val next = withTimeout(45_000) { T3Repository.from(context, settings).fetch(previous) }
                return publish(context, next.copy(live = LiveUpdateService.isConnected))
            } catch (c: CancellationException) {
                if (c is TimeoutCancellationException) {
                    return publishError(context, ErrorKind.UNREACHABLE, "PC unreachable (timeout)")
                }
                throw c
            } catch (t: Throwable) {
                val (kind, msg) = classify(context, t)
                val retryable = kind == ErrorKind.UNREACHABLE || kind == ErrorKind.SERVER
                if (retryable && attempt < BACKOFF_MS.size) {
                    delay(BACKOFF_MS[attempt++])
                    continue
                }
                Log.w(TAG, "refresh failed: $kind $msg")
                if (kind == ErrorKind.OFFLINE) refreshWhenOnline(context)
                return publishError(context, kind, msg)
            }
        }
    }

    fun classify(context: Context, t: Throwable): Pair<ErrorKind, String> = when {
        t is ApiException -> t.kind to (t.message ?: t.kind.name)
        currentNetClass(context) == NetClass.NONE -> ErrorKind.OFFLINE to "Offline"
        t is IOException -> ErrorKind.UNREACHABLE to "PC unreachable"
        else -> ErrorKind.OTHER to (t.message ?: t::class.java.simpleName)
    }

    /** Save a good state and redraw. */
    suspend fun publish(context: Context, next: WidgetState): WidgetState {
        Store(context).saveState(next)
        redraw(context)
        return next
    }

    /** Keep the last numbers, mark the error, redraw. */
    suspend fun publishError(context: Context, kind: ErrorKind, message: String): WidgetState {
        val s = Store(context).updateState { it.copy(error = message, errorKind = kind, live = false) }
        redraw(context)
        return s
    }

    suspend fun redraw(context: Context) {
        T3Widget().updateAll(context)
        LiveUpdateService.updateNotification(context)
    }

    /** Periodic refresh every 15 minutes (WorkManager's minimum). Also restarts the live service if it died. */
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Manual refresh (tap on the usage area, or Settings > Refresh now). */
    fun refreshNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, req)
    }

    /** Runs as soon as the phone has a network again (when the live service isn't there to notice). */
    fun refreshWhenOnline(context: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ON_NETWORK, ExistingWorkPolicy.KEEP, req)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(ON_NETWORK)
        }
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // Watchdog: (re)start the live service if it should be running. Allowed from the background when the
        // app is exempt from battery optimisation; otherwise this just falls through to a one-shot refresh.
        LiveUpdateService.startIfWanted(ctx, fromBackground = true)
        if (LiveUpdateService.isConnected) {
            // The socket is live; just redraw so relative times stay correct.
            Refresher.redraw(ctx)
            return Result.success()
        }
        Refresher.refresh(ctx)
        return Result.success()
    }
}
