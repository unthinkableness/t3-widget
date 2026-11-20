package dev.local.t3widget.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Receives PackageInstaller session status for self-updates (not exported; only our PendingIntent reaches it). */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val code = intent.getIntExtra(EXTRA_VERSION_CODE, 0)
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val confirm = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        } else null
        val pending = goAsync()
        scope.launch {
            try {
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Updater.onPendingUserAction(context, code, sessionId, confirm)
                } else {
                    Updater.onInstallFinished(context, code, sessionId, status, message)
                }
            } catch (t: Throwable) {
                Log.w("T3Update", "install status handling failed: $t")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STATUS = "dev.local.t3widget.action.UPDATE_INSTALL_STATUS"
        const val EXTRA_VERSION_CODE = "dev.local.t3widget.extra.VERSION_CODE"
        const val EXTRA_SESSION_ID = "dev.local.t3widget.extra.SESSION_ID"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
