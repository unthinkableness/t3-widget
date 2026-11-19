package dev.local.t3widget.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.local.t3widget.R
import dev.local.t3widget.ui.SettingsActivity

/** Update notifications. All calls are best-effort (no permission / channel blocked = nothing shown). */
object UpdateNotifier {
    private const val CHANNEL_READY = "app_update_ready"
    private const val CHANNEL_STATUS = "app_update_status"
    private const val ID_READY = 41
    private const val ID_STATUS = 42

    /** "Update ready, tap to install": high priority, opens settings which shows the confirmation. */
    fun ready(context: Context, versionName: String, detail: String?) {
        post(context, ID_READY, CHANNEL_READY) {
            setContentTitle("T3 Widget update ready, tap to install")
            setContentText(detail?.takeIf { it.isNotBlank() } ?: "Version $versionName")
            setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull("Version $versionName", detail?.takeIf { it.isNotBlank() }).joinToString("\n")))
            setPriority(NotificationCompat.PRIORITY_HIGH)
            setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            setContentIntent(installIntent(context))
            addAction(0, "Install", installIntent(context))
            setAutoCancel(true)
        }
    }

    /** Auto-install off: "Update available" with an Install button. */
    fun available(context: Context, info: UpdateCore.VersionInfo) {
        post(context, ID_READY, CHANNEL_STATUS) {
            setContentTitle("T3 Widget update available")
            setContentText("Version ${info.versionName}" + if (info.notes.isNotBlank()) " · ${info.notes}" else "")
            setStyle(NotificationCompat.BigTextStyle().bigText(listOf("Version ${info.versionName}", info.notes).filter { it.isNotBlank() }.joinToString("\n")))
            setContentIntent(settingsIntent(context))
            addAction(0, "Install", installIntent(context))
            setAutoCancel(true)
        }
    }

    fun result(context: Context, title: String, detail: String?, success: Boolean) {
        post(context, ID_STATUS, CHANNEL_STATUS) {
            setContentTitle(title)
            if (!detail.isNullOrBlank()) {
                setContentText(detail)
                setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            }
            setContentIntent(settingsIntent(context))
            setAutoCancel(true)
            if (success) setTimeoutAfter(6 * 60 * 60_000L)
        }
    }

    fun cancelReady(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_READY) }
    }

    private fun installIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(context, SettingsActivity::class.java).setAction(SettingsActivity.ACTION_INSTALL_UPDATE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun settingsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 2,
        Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_READY) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_READY, "App update ready", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A downloaded T3 Widget update needs you to confirm the install."
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_STATUS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Update available, installed, or failed."
                }
            )
        }
    }

    private fun post(context: Context, id: Int, channel: String, build: NotificationCompat.Builder.() -> Unit) {
        try {
            val ctx = context.applicationContext
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            ensureChannels(ctx)
            val n = NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_stat_t3)
                .setOnlyAlertOnce(true)
                .apply(build)
                .build()
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: Exception) {
            Log.w("T3Update", "notification failed: $e")
        }
    }
}
