package dev.local.t3widget.update

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Runs [Updater] in WorkManager so checks/downloads survive the UI going away and never touch the main thread. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val trigger = Updater.Trigger.entries.firstOrNull { it.name == inputData.getString(KEY_TRIGGER) }
            ?: Updater.Trigger.PERIODIC
        try {
            val r = if (trigger == Updater.Trigger.INSTALL) Updater.install(applicationContext)
            else Updater.check(applicationContext, trigger)
            if (r == Updater.RunResult.NETWORK && trigger != Updater.Trigger.INSTALL) {
                // Back off (15 min, 30 min, … 6 h, or until the rate-limit reset) and try again on a network.
                val next = UpdateStore(applicationContext).state().nextAutoCheckAt
                scheduleRetry(applicationContext, (next - System.currentTimeMillis()).coerceAtLeast(60_000L))
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w("T3Update", "update worker failed", t)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_TRIGGER = "trigger"
        private const val PERIODIC = "t3widget-update-periodic"
        private const val NOW = "t3widget-update-now"
        private const val RETRY = "t3widget-update-retry"
        private const val INSTALL = "t3widget-update-install"

        private val connected = Constraints(requiredNetworkType = NetworkType.CONNECTED)

        /** Every 6 hours on any network. */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(connected)
                .setInputData(workDataOf(KEY_TRIGGER to Updater.Trigger.PERIODIC.name))
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
            }
        }

        /** App opened: a throttled check once a network is available. */
        fun checkOnAppOpen(context: Context) = enqueue(context, NOW, Updater.Trigger.APP_OPEN, ExistingWorkPolicy.KEEP, connected, 0)

        /** Settings button: run now (no network constraint, so "offline" is reported instead of waiting). */
        fun checkNow(context: Context) = enqueue(context, NOW, Updater.Trigger.MANUAL, ExistingWorkPolicy.REPLACE, Constraints.NONE, 0)

        fun installNow(context: Context) = enqueue(context, INSTALL, Updater.Trigger.INSTALL, ExistingWorkPolicy.KEEP, Constraints.NONE, 0)

        private fun scheduleRetry(context: Context, delayMs: Long) =
            enqueue(context, RETRY, Updater.Trigger.RETRY, ExistingWorkPolicy.REPLACE, connected, delayMs)

        private fun enqueue(context: Context, name: String, trigger: Updater.Trigger, policy: ExistingWorkPolicy, c: Constraints, delayMs: Long) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(c)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_TRIGGER to trigger.name))
                .build()
            runCatching { WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, policy, req) }
                .onFailure { Log.w("T3Update", "enqueue $name failed: $it") }
        }
    }
}
