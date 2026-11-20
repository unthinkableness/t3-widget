package dev.local.t3widget.update

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.glance.appwidget.updateAll
import dev.local.t3widget.BuildConfig
import dev.local.t3widget.widget.T3Widget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Self-updater: checks GitHub releases ([UpdateCore.REPO]), downloads the APK to the cache, verifies its SHA-256
 * against version.json and installs it with a PackageInstaller session. Everything runs off the main thread
 * (WorkManager / receivers); all failures are caught and recorded in [UpdateStore], never thrown to callers.
 */
object Updater {
    private const val TAG = "T3Update"

    enum class Trigger { PERIODIC, APP_OPEN, MANUAL, RETRY, INSTALL }

    /** What a run ended with; NETWORK means "schedule a retry with backoff". */
    enum class RunResult { DONE, NETWORK, SKIPPED }

    /** App-open checks run at most this often (ETag makes them cheap, but GitHub allows 60 calls/h per IP). */
    private const val APP_OPEN_MIN_INTERVAL_MS = 10 * 60_000L
    private const val MAX_APK_BYTES = 64L * 1024 * 1024
    private const val MAX_JSON_BYTES = 256L * 1024

    private val mutex = Mutex()

    private val _busy = MutableStateFlow<String?>(null)
    /** "Checking…" / "Downloading 0.4.0… 40%" while a run is active (process-local, for the settings screen). */
    val busy: StateFlow<String?> = _busy

    /** The installer's confirmation intent for the session waiting on the user (lost if the process dies). */
    @Volatile private var pendingConfirm: Intent? = null
    @Volatile private var pendingConfirmCode: Int = 0

    @Volatile private var resumedSettings: WeakReference<Activity>? = null

    fun attachSettings(activity: Activity) {
        resumedSettings = WeakReference(activity)
    }

    fun detachSettings(activity: Activity) {
        if (resumedSettings?.get() === activity) resumedSettings = null
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
    }

    val installedCode: Int get() = BuildConfig.VERSION_CODE
    val installedName: String get() = BuildConfig.VERSION_NAME

    private class RateLimited(val resetAt: Long) : IOException("GitHub rate limit")
    private class UpdateFailure(message: String, val versionCode: Int = 0, val permanent: Boolean = false) :
        Exception(message)

    // ------------------------------------------------------------------ entry points

    /** Check for an update and, if newer and auto-install is on, download and install it. Never throws. */
    suspend fun check(context: Context, trigger: Trigger): RunResult = guarded(context) { ctx ->
        val store = UpdateStore(ctx)
        val st0 = store.state()
        if (hasOpenSession(ctx, st0)) return@guarded RunResult.DONE
        val now = System.currentTimeMillis()
        val automatic = trigger != Trigger.MANUAL
        // The backoff gate applies to periodic/app-open checks; the scheduled retry is what it was waiting for.
        if (automatic && trigger != Trigger.RETRY && now < st0.nextAutoCheckAt) return@guarded RunResult.SKIPPED
        if (trigger == Trigger.APP_OPEN && now - st0.lastCheckAt < APP_OPEN_MIN_INTERVAL_MS) return@guarded RunResult.SKIPPED

        _busy.value = "Checking for updates…"
        val latest = fetchLatest(store) ?: run {
            store.update {
                it.copy(
                    lastCheckAt = now, outcome = CheckOutcome.NO_RELEASES, message = "No releases published yet",
                    failures = 0, nextAutoCheckAt = 0,
                )
            }
            return@guarded RunResult.DONE
        }
        val (release, info) = latest
        if (!UpdateCore.isNewer(info.versionCode, installedCode)) {
            store.update {
                it.copy(
                    lastCheckAt = now, outcome = CheckOutcome.UP_TO_DATE, message = "Up to date",
                    latestCode = info.versionCode, latestName = info.versionName, latestNotes = info.notes,
                    readyCode = 0, readyPath = null, awaitingUser = false, needsPermission = false,
                    failures = 0, nextAutoCheckAt = 0,
                )
            }
            clearDownloads(ctx)
            redrawWidgets(ctx)
            return@guarded RunResult.DONE
        }
        if (info.minSdk > Build.VERSION.SDK_INT) {
            store.update {
                it.copy(
                    lastCheckAt = now, outcome = CheckOutcome.ERROR, latestCode = info.versionCode,
                    latestName = info.versionName, latestNotes = info.notes, failures = 0, nextAutoCheckAt = 0,
                    message = "${info.versionName} needs Android API ${info.minSdk}; this phone has ${Build.VERSION.SDK_INT}",
                )
            }
            return@guarded RunResult.DONE
        }

        val st = store.update {
            it.copy(
                lastCheckAt = now, failures = 0, nextAutoCheckAt = 0,
                latestCode = info.versionCode, latestName = info.versionName, latestNotes = info.notes,
                outcome = if (it.readyCode == info.versionCode && it.awaitingUser) it.outcome else CheckOutcome.AVAILABLE,
                message = if (it.readyCode == info.versionCode && it.awaitingUser) it.message
                else "Update ${info.versionName} available",
            )
        }
        val auto = store.autoInstall()
        when {
            // Declined or failed before: don't nag automatically; the Install button still works.
            automatic && st.failedCode == info.versionCode -> RunResult.DONE
            !auto -> {
                if (automatic && st.notifiedCode != info.versionCode) {
                    UpdateNotifier.available(ctx, info)
                    store.update { it.copy(notifiedCode = info.versionCode) }
                }
                RunResult.DONE
            }
            else -> {
                downloadAndInstall(ctx, store, release, info)
                RunResult.DONE
            }
        }
    }

    /** User asked to install (button / notification): uses the cached release info, or fetches it. Never throws. */
    suspend fun install(context: Context): RunResult = guarded(context) { ctx ->
        val store = UpdateStore(ctx)
        _busy.value = "Preparing update…"
        val st = store.state()
        // A confirmation intent is in-memory. After process death, a user can replace a waiting session.
        if (hasOpenSession(ctx, st) && (!st.awaitingUser || pendingConfirm != null)) return@guarded RunResult.DONE
        val cached = UpdateCore.Release.fromJson(st.release)?.let { r ->
            st.versionJson?.let { v -> runCatching { r to UpdateCore.parseVersionJson(v) }.getOrNull() }
        }
        val latest = cached ?: fetchLatest(store)
        if (latest == null) {
            store.update { it.copy(outcome = CheckOutcome.NO_RELEASES, message = "No releases published yet") }
            return@guarded RunResult.DONE
        }
        val (release, info) = latest
        if (!UpdateCore.isNewer(info.versionCode, installedCode)) {
            store.update { it.copy(outcome = CheckOutcome.UP_TO_DATE, message = "Up to date", readyCode = 0, readyPath = null, awaitingUser = false) }
            redrawWidgets(ctx)
            return@guarded RunResult.DONE
        }
        downloadAndInstall(ctx, store, release, info, requireConfirmation = true)
        RunResult.DONE
    }

    /**
     * After the app was replaced (MY_PACKAGE_REPLACED, or the next check): report a finished self-update and
     * drop stale downloads. Safe to call any time; never throws.
     */
    suspend fun reconcile(context: Context) {
        try {
            val ctx = context.applicationContext
            val store = UpdateStore(ctx)
            val st = store.state()
            val target = maxOf(st.installingCode, st.readyCode)
            if (target == 0 || installedCode < target) return
            val weInstalled = st.installingCode != 0 && installedCode >= st.installingCode
            store.update {
                it.copy(
                    outcome = if (weInstalled) CheckOutcome.INSTALLED else it.outcome,
                    message = if (weInstalled) "Updated to $installedName" else it.message,
                    readyCode = 0, readyPath = null, awaitingUser = false, needsPermission = false,
                    installingCode = 0, installingSessionId = null, failedCode = 0,
                )
            }
            pendingConfirm = null
            clearDownloads(ctx)
            UpdateNotifier.cancelReady(ctx)
            if (weInstalled) UpdateNotifier.result(ctx, "T3 Widget updated to $installedName", null, success = true)
            redrawWidgets(ctx)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "reconcile failed: $t")
        }
    }

    /**
     * Settings "Install" button / "Update ready" notification, with the activity in front: re-show a pending
     * confirmation, send the user to "Install unknown apps", or start a fresh install.
     */
    fun promptInstall(activity: Activity) {
        UpdateNotifier.cancelReady(activity)
        val confirm = pendingConfirm
        if (confirm != null && pendingConfirmCode > installedCode) {
            try {
                activity.startActivity(confirm)
                return
            } catch (e: Exception) {
                Log.w(TAG, "confirmation intent failed, starting a new session: $e")
                pendingConfirm = null
            }
        }
        // Without "Install unknown apps" the worker downloads and verifies, then waits (needsPermission);
        // onSettingsResumed() continues once the user grants it.
        UpdateWorker.installNow(activity)
        if (!canInstall(activity)) openInstallPermissionSettings(activity)
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings > Apps > Special access > Install unknown apps > T3 Widget. */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** Settings resumed: if we were only waiting for the install permission and it's granted now, continue. */
    suspend fun onSettingsResumed(context: Context) {
        try {
            val st = UpdateStore(context).state()
            if (st.needsPermission && st.readyCode > installedCode && canInstall(context)) UpdateWorker.installNow(context)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "resume check failed: $t")
        }
    }

    fun showsWidgetIndicator(s: UpdateState): Boolean = s.awaitingUser && s.readyCode > installedCode

    fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

    // ------------------------------------------------------------------ install results (from InstallReceiver)

    internal suspend fun onPendingUserAction(context: Context, code: Int, sessionId: Int, confirm: Intent?) = mutex.withLock {
        val ctx = context.applicationContext
        val store = UpdateStore(ctx)
        val st = store.state()
        if (!st.isInstalling(code, sessionId)) return@withLock
        val name = st.latestName?.takeIf { st.latestCode == code } ?: "the update"
        pendingConfirm = confirm
        pendingConfirmCode = code
        store.update {
            it.copy(outcome = CheckOutcome.READY, awaitingUser = true, needsPermission = false, message = "$name is ready; confirm the install")
        }
        val launched = withContext(Dispatchers.Main.immediate) {
            val activity = resumedSettings?.get()
            activity != null && confirm != null && runCatching { activity.startActivity(confirm) }.isSuccess
        }
        if (!launched) UpdateNotifier.ready(ctx, name, st.latestNotes)
        redrawWidgets(ctx)
    }

    internal suspend fun onInstallFinished(context: Context, code: Int, sessionId: Int, status: Int, message: String?) = mutex.withLock {
        val ctx = context.applicationContext
        val store = UpdateStore(ctx)
        if (!store.state().isInstalling(code, sessionId)) return@withLock
        pendingConfirm = null
        if (status == PackageInstaller.STATUS_SUCCESS) {
            // Usually not delivered (this process is replaced first); reconcile() in the new version reports it.
            store.update { it.copy(installingCode = code) }
            reconcile(ctx)
            return@withLock
        }
        val aborted = status == PackageInstaller.STATUS_FAILURE_ABORTED
        val signature = UpdateCore.isSignatureMismatch(message)
        val reason = UpdateCore.describeInstallFailure(status, message)
        Log.w(TAG, "install of $code failed: status=$status $message")
        store.update {
            it.copy(
                outcome = if (aborted) CheckOutcome.READY else CheckOutcome.ERROR,
                message = UpdateCore.installFailureMessage(status, message),
                installingCode = 0, installingSessionId = null, failedCode = code,
                // Cancelled: keep the verified APK and the widget dot. A signature mismatch can never succeed.
                awaitingUser = aborted,
                readyCode = if (signature) 0 else it.readyCode,
                readyPath = if (signature) null else it.readyPath,
            )
        }
        if (signature) clearDownloads(ctx)
        UpdateNotifier.cancelReady(ctx)
        if (!aborted) UpdateNotifier.result(ctx, "T3 Widget update failed", reason, success = false)
        redrawWidgets(ctx)
    }

    // ------------------------------------------------------------------ internals

    /** Lock, catch everything, record failures with backoff. */
    private suspend fun guarded(context: Context, block: suspend (Context) -> RunResult): RunResult {
        val ctx = context.applicationContext
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    reconcile(ctx)
                    block(ctx)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: RateLimited) {
                    recordFailure(ctx, "GitHub rate limit reached; next check ${formatTime(e.resetAt)}", e.resetAt)
                    RunResult.NETWORK
                } catch (e: IOException) {
                    Log.w(TAG, "update network error: $e")
                    recordFailure(ctx, "Couldn't reach GitHub (${e.message ?: e.javaClass.simpleName})", null)
                    RunResult.NETWORK
                } catch (e: UpdateFailure) {
                    Log.w(TAG, "update failed: ${e.message}")
                    UpdateStore(ctx).update {
                        it.copy(
                            lastCheckAt = System.currentTimeMillis(), outcome = CheckOutcome.ERROR, message = e.message,
                            failedCode = if (e.permanent) e.versionCode else it.failedCode,
                            awaitingUser = false, needsPermission = false, installingCode = 0, installingSessionId = null,
                            readyCode = 0, readyPath = null,
                        )
                    }
                    UpdateNotifier.result(ctx, "T3 Widget update failed", e.message, success = false)
                    redrawWidgets(ctx)
                    RunResult.DONE
                } catch (t: Throwable) {
                    Log.w(TAG, "update check failed", t)
                    UpdateStore(ctx).update {
                        it.copy(
                            lastCheckAt = System.currentTimeMillis(), outcome = CheckOutcome.ERROR,
                            message = "Update check failed: ${t.message ?: t.javaClass.simpleName}",
                            nextAutoCheckAt = System.currentTimeMillis() + 60 * 60_000L,
                        )
                    }
                    RunResult.DONE
                } finally {
                    _busy.value = null
                }
            }
        }
    }

    private suspend fun recordFailure(ctx: Context, message: String, until: Long?) {
        val now = System.currentTimeMillis()
        UpdateStore(ctx).update {
            val failures = it.failures + 1
            val next = until ?: (now + UpdateCore.backoffMs(failures))
            it.copy(
                lastCheckAt = now, outcome = CheckOutcome.NETWORK_ERROR, failures = failures, nextAutoCheckAt = next,
                message = message,
            )
        }
    }

    private fun get(url: String, etag: String? = null): Response {
        val b = Request.Builder().url(url).header("User-Agent", UpdateCore.USER_AGENT)
        if (url.startsWith("https://api.github.com/")) {
            b.header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (etag != null) b.header("If-None-Match", etag)
        return client.newCall(b.build()).execute()
    }

    /** releases/latest (+ its version.json), using the ETag cache. Null = no release yet (404). */
    private suspend fun fetchLatest(store: UpdateStore): Pair<UpdateCore.Release, UpdateCore.VersionInfo>? {
        val st = store.state()
        val haveCache = st.etag != null && st.release != null && st.versionJson != null
        var release: UpdateCore.Release
        var newEtag: String?
        get(UpdateCore.LATEST_RELEASE_URL, if (haveCache) st.etag else null).use { res ->
            when {
                res.code == 304 && haveCache -> {
                    val cachedRelease = UpdateCore.Release.fromJson(st.release)
                    val cachedInfo = runCatching { UpdateCore.parseVersionJson(st.versionJson!!) }.getOrNull()
                    if (cachedRelease != null && cachedInfo != null) return cachedRelease to cachedInfo
                    // Corrupt cache: drop it and fetch fresh next time.
                    store.update { it.copy(etag = null, release = null, versionJson = null) }
                    throw IOException("cache invalid; retrying")
                }
                res.code == 404 -> {
                    store.update { it.copy(etag = null, release = null, versionJson = null) }
                    return null
                }
                res.code == 429 || (res.code == 403 && res.header("X-RateLimit-Remaining") == "0") -> {
                    val reset = res.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                    throw RateLimited(reset?.coerceAtLeast(System.currentTimeMillis() + 60_000) ?: (System.currentTimeMillis() + 60 * 60_000L))
                }
                !res.isSuccessful -> throw IOException("GitHub HTTP ${res.code}")
            }
            release = try {
                UpdateCore.parseLatestRelease(readLimited(res, MAX_JSON_BYTES))
            } catch (e: UpdateCore.ParseException) {
                throw UpdateFailure(e.message ?: "Bad release")
            }
            newEtag = res.header("ETag")
        }
        val versionJson = get(release.versionUrl).use { res ->
            if (!res.isSuccessful) throw IOException("version.json HTTP ${res.code}")
            readLimited(res, MAX_JSON_BYTES)
        }
        val info = try {
            UpdateCore.parseVersionJson(versionJson)
        } catch (e: UpdateCore.ParseException) {
            throw UpdateFailure(e.message ?: "Bad version.json")
        }
        store.update { it.copy(etag = newEtag, release = release.toJson(), versionJson = versionJson) }
        return release to info
    }

    private fun readLimited(res: Response, max: Long): String {
        val body = res.body ?: throw IOException("empty response")
        if (body.contentLength() > max) throw IOException("response too large")
        val source = body.source()
        source.request(max + 1)
        if (source.buffer.size > max) throw IOException("response too large")
        return source.buffer.readUtf8()
    }

    private fun updatesDir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }
    private fun apkFile(ctx: Context, code: Int) = File(updatesDir(ctx), "t3-widget-$code.apk")

    private fun clearDownloads(ctx: Context) {
        runCatching { File(ctx.cacheDir, "updates").listFiles()?.forEach { it.delete() } }
    }

    private suspend fun downloadAndInstall(
        ctx: Context, store: UpdateStore, release: UpdateCore.Release, info: UpdateCore.VersionInfo,
        requireConfirmation: Boolean = false,
    ) {
        val file = apkFile(ctx, info.versionCode)
        val st = store.state()
        val reuse = st.readyCode == info.versionCode && file.isFile &&
            UpdateCore.hashMatches(info.sha256, file.inputStream().use { UpdateCore.sha256Hex(it) })
        if (!reuse) {
            updatesDir(ctx).listFiles()?.forEach { it.delete() }
            store.update { it.copy(outcome = CheckOutcome.DOWNLOADING, message = "Downloading ${info.versionName}…", readyCode = 0, readyPath = null) }
            download(release.apkUrl, file, info)
        }
        verifyArchive(ctx, file, info)
        store.update {
            it.copy(readyCode = info.versionCode, readyPath = file.absolutePath, outcome = CheckOutcome.READY, message = "${info.versionName} downloaded")
        }

        if (!canInstall(ctx)) {
            store.update {
                it.copy(
                    awaitingUser = true, needsPermission = true, outcome = CheckOutcome.READY,
                    message = "${info.versionName} downloaded. Allow \"Install unknown apps\" for T3 Widget to install it.",
                )
            }
            redrawWidgets(ctx)
            if (resumedSettings?.get() == null) UpdateNotifier.ready(ctx, info.versionName, "Allow T3 Widget to install updates, then tap Install.")
            return
        }
        commit(ctx, store, file, info, requireConfirmation)
    }

    private fun download(url: String, dest: File, info: UpdateCore.VersionInfo) {
        val part = File(dest.path + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        get(url).use { res ->
            if (!res.isSuccessful) throw IOException("APK download HTTP ${res.code}")
            val body = res.body ?: throw IOException("empty APK download")
            val total = body.contentLength()
            if (total > MAX_APK_BYTES) throw UpdateFailure("APK is too large ($total bytes)")
            var done = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > MAX_APK_BYTES) throw UpdateFailure("APK is too large")
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct / 5 != lastPct / 5) {
                                lastPct = pct
                                _busy.value = "Downloading ${info.versionName}… $pct%"
                            }
                        }
                    }
                }
            }
        }
        val actual = UpdateCore.toHex(md.digest())
        if (!UpdateCore.hashMatches(info.sha256, actual)) {
            part.delete()
            throw UpdateFailure(
                "Downloaded APK failed the SHA-256 check; not installed (expected ${info.sha256.take(12)}…, got ${actual.take(12)}…)",
                info.versionCode, permanent = true,
            )
        }
        if (!part.renameTo(dest)) {
            part.delete()
            throw IOException("couldn't save the APK")
        }
    }

    /** Package name, versionCode and signing key of the downloaded APK must match before we hand it to Android. */
    @SuppressLint("PackageManagerGetSignatures")
    private fun verifyArchive(ctx: Context, file: File, info: UpdateCore.VersionInfo) {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive: PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.path, flags)
        }
        if (archive == null) {
            file.delete()
            throw UpdateFailure("Downloaded file isn't a valid APK", info.versionCode)
        }
        if (archive.packageName != ctx.packageName) {
            file.delete()
            throw UpdateFailure("APK is for ${archive.packageName}, not ${ctx.packageName}; not installed", info.versionCode, permanent = true)
        }
        val code = PackageInfoCompat.getLongVersionCode(archive)
        if (code != info.versionCode.toLong()) {
            file.delete()
            throw UpdateFailure("APK versionCode $code doesn't match version.json (${info.versionCode}); not installed", info.versionCode, permanent = true)
        }
        val mine = runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else pm.getPackageInfo(ctx.packageName, flags)
        }.getOrNull()
        val theirs = signerDigests(archive)
        val ours = mine?.let { signerDigests(it) }
        // Best effort: only fail when both sides are known and share no certificate.
        if (theirs.isNotEmpty() && !ours.isNullOrEmpty() && theirs.intersect(ours).isEmpty()) {
            file.delete()
            throw UpdateFailure(
                "Signature mismatch: ${info.versionName} is signed with a different key than the installed app, " +
                    "so Android would reject it. Not installed; reinstall manually (uninstall first) or fix the release signing.",
                info.versionCode, permanent = true,
            )
        }
    }

    private fun signerDigests(p: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo ?: return emptySet()
            (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory) ?: return emptySet()
        } else {
            p.signatures ?: return emptySet()
        }
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { UpdateCore.toHex(md.digest(it.toByteArray())) }.toSet()
    }

    private fun hasOpenSession(ctx: Context, state: UpdateState): Boolean {
        val sessionId = state.installingSessionId ?: return false
        return ctx.packageManager.packageInstaller.getSessionInfo(sessionId) != null
    }

    /** Stream the APK into a session (hashing what is written), then commit. Abandons on any problem. */
    private suspend fun commit(ctx: Context, store: UpdateStore, file: File, info: UpdateCore.VersionInfo, requireConfirmation: Boolean) {
        val installer = ctx.packageManager.packageInstaller
        // Only one of our sessions at a time.
        runCatching { installer.mySessions.forEach { s -> runCatching { installer.abandonSession(s.sessionId) } } }
        pendingConfirm = null

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) {
                val userAction = if (requireConfirmation) PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                else PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                setRequireUserAction(userAction)
            }
        }
        _busy.value = "Installing ${info.versionName}…"
        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                val md = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                        session.fsync(out)
                    }
                }
                // What goes into the session is exactly what was hashed: never commit a mismatching APK.
                if (!UpdateCore.hashMatches(info.sha256, UpdateCore.toHex(md.digest()))) {
                    file.delete()
                    throw UpdateFailure("APK changed after download and failed the SHA-256 check; not installed", info.versionCode)
                }
                store.update {
                    it.copy(
                        installingCode = info.versionCode, installingSessionId = sessionId,
                        awaitingUser = false, needsPermission = false,
                        outcome = CheckOutcome.INSTALLING, message = "Installing ${info.versionName}…",
                    )
                }
                val intent = Intent(ctx, InstallReceiver::class.java)
                    .setAction(InstallReceiver.ACTION_STATUS)
                    .setPackage(ctx.packageName)
                    .putExtra(InstallReceiver.EXTRA_VERSION_CODE, info.versionCode)
                    .putExtra(InstallReceiver.EXTRA_SESSION_ID, sessionId)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val sender = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
                session.commit(sender.intentSender)
                committed = true
            }
        } finally {
            if (!committed) {
                runCatching { installer.abandonSession(sessionId) }
                store.update {
                    if (it.isInstalling(info.versionCode, sessionId)) it.copy(installingCode = 0, installingSessionId = null) else it
                }
            }
        }
    }

    suspend fun redrawWidgets(ctx: Context) {
        try {
            T3Widget().updateAll(ctx)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "widget redraw failed: $t")
        }
    }
}
