package dev.local.t3widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.local.t3widget.data.Settings
import dev.local.t3widget.ui.SettingsActivity

/** Intents that open the T3 Code Android app (release, then preview). */
object T3Links {
    val packages: List<String> = BuildConfig.T3_APP_PACKAGES.split(',').filter { it.isNotBlank() }

    /** T3's own thread link format; the app verifies App Links for app.t3.codes. */
    fun threadUrl(environmentId: String, threadId: String): String =
        "https://app.t3.codes/threads/${Uri.encode(environmentId)}/${Uri.encode(threadId)}"

    fun installedPackage(context: Context): String? =
        packages.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

    /** Card tap: the T3 app's launcher intent; else the server web UI; else this app's settings. */
    fun openApp(context: Context, settings: Settings?): Intent {
        val pm = context.packageManager
        val intent = packages.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
            ?: settings?.serverUrl?.takeIf { !settings.useFakeData && it.startsWith("http") }
                ?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
            ?: Intent(context, SettingsActivity::class.java)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Opens a thread: ACTION_VIEW on the thread URL targeted at the installed T3 package (release, then preview);
     * without one installed, the plain URL (browser / app chooser). Falls back to [openApp] without ids.
     */
    fun openThread(context: Context, environmentId: String?, threadId: String?, settings: Settings?): Intent {
        if (environmentId.isNullOrBlank() || threadId.isNullOrBlank()) return openApp(context, settings)
        val uri = Uri.parse(threadUrl(environmentId, threadId))
        val pm = context.packageManager
        for (pkg in packages) {
            if (pm.getLaunchIntentForPackage(pkg) == null) continue
            val i = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addCategory(Intent.CATEGORY_BROWSABLE)
            if (i.resolveActivity(pm) != null) return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // T3 installed but its filter doesn't match this path: just launch it.
        if (installedPackage(context) != null) return openApp(context, settings)
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
