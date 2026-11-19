package dev.local.t3widget.update

import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

/**
 * Pure parts of the self-updater (no Android context): release / version.json parsing, version
 * comparison, hashing, backoff and install-status wording. Unit-tested on the JVM.
 */
object UpdateCore {
    /** The public GitHub repo that publishes releases. Change only this to move the update source. */
    const val REPO = "unthinkableness/t3-widget"
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val USER_AGENT = "t3-widget-updater"
    const val APK_ASSET = "t3-widget.apk"
    const val VERSION_ASSET = "version.json"

    /** Automatic (periodic / app-open) checks after a network error wait 15 min, 30, 1 h, 2 h, … up to 6 h. */
    private const val BACKOFF_BASE_MS = 15 * 60_000L
    const val MAX_BACKOFF_MS = 6 * 60 * 60_000L

    class ParseException(message: String) : Exception(message)

    /** The two assets of the latest release that the updater needs. */
    data class Release(val tag: String, val apkUrl: String, val versionUrl: String) {
        fun toJson(): String = JSONObject().put("tag", tag).put("apkUrl", apkUrl).put("versionUrl", versionUrl).toString()

        companion object {
            fun fromJson(s: String?): Release? = runCatching {
                val o = JSONObject(s ?: return null)
                Release(o.getString("tag"), o.getString("apkUrl"), o.getString("versionUrl"))
            }.getOrNull()
        }
    }

    /** Contents of a release's version.json. */
    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val sha256: String,
        val notes: String,
        val minSdk: Int,
    )

    /** `GET /repos/{repo}/releases/latest` → asset download URLs. Throws [ParseException] if an asset is missing. */
    fun parseLatestRelease(json: String): Release {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw ParseException("Release response isn't JSON")
        }
        val tag = o.optString("tag_name").ifBlank { throw ParseException("Release has no tag") }
        val assets = o.optJSONArray("assets") ?: throw ParseException("Release $tag has no assets")
        var apk: String? = null
        var version: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val url = a.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: continue
            when (a.optString("name")) {
                APK_ASSET -> apk = url
                VERSION_ASSET -> version = url
            }
        }
        if (version == null) throw ParseException("Release $tag has no $VERSION_ASSET")
        if (apk == null) throw ParseException("Release $tag has no $APK_ASSET")
        return Release(tag, apk, version)
    }

    /** version.json → [VersionInfo]. Strict: a bad hash or version means "don't install". */
    fun parseVersionJson(json: String): VersionInfo {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw ParseException("$VERSION_ASSET isn't JSON")
        }
        val code = o.optInt("versionCode", 0)
        if (code <= 0) throw ParseException("$VERSION_ASSET has no valid versionCode")
        val name = o.optString("versionName").trim().ifBlank { throw ParseException("$VERSION_ASSET has no versionName") }
        val sha = normalizeSha256(o.optString("sha256")) ?: throw ParseException("$VERSION_ASSET has no valid sha256")
        val notes = if (o.isNull("notes")) "" else o.optString("notes").trim()
        val minSdk = o.optInt("minSdk", 1)
        return VersionInfo(code, name, sha, notes, minSdk)
    }

    /** Lower-case 64-char hex, or null if [s] isn't a SHA-256 hex string. */
    fun normalizeSha256(s: String?): String? {
        val t = s?.trim()?.lowercase() ?: return null
        return t.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** Only strictly newer builds are offered; same or older versionCodes are ignored. */
    fun isNewer(remoteCode: Int, installedCode: Int): Boolean = remoteCode > installedCode

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return toHex(md.digest())
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** True only when both are valid SHA-256 hex strings and equal (case-insensitive). */
    fun hashMatches(expected: String?, actual: String?): Boolean {
        val e = normalizeSha256(expected) ?: return false
        val a = normalizeSha256(actual) ?: return false
        return MessageDigest.isEqual(e.toByteArray(), a.toByteArray())
    }

    /** Delay before the next automatic check after [failures] consecutive network failures (>= 1). */
    fun backoffMs(failures: Int): Long {
        if (failures <= 0) return 0
        val shift = (failures - 1).coerceAtMost(10)
        return (BACKOFF_BASE_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** True when an installer failure means the APK was signed with a different key than the installed app. */
    fun isSignatureMismatch(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return "update_incompatible" in m || "signatures do not match" in m || "signature" in m && "match" in m ||
            "inconsistent_certificates" in m || "no_certificates" in m
    }

    /** Human wording for a PackageInstaller failure status (+ its EXTRA_STATUS_MESSAGE). */
    fun describeInstallFailure(status: Int, message: String?): String {
        if (isSignatureMismatch(message)) {
            return "Signature mismatch: the update is signed with a different key than the installed app. " +
                "Android won't install it over this version; reinstall manually (uninstall first)."
        }
        val base = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Blocked by the system or a security setting (e.g. Samsung Auto Blocker)"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflicts with the installed app"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Not compatible with this phone"
            PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is invalid or corrupt"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
            STATUS_FAILURE_TIMEOUT -> "Timed out waiting for the installer"
            else -> "Install failed (status $status)"
        }
        val detail = message?.trim()?.takeIf { it.isNotEmpty() }
        return if (detail != null) "$base ($detail)" else base
    }

    /** PackageInstaller.STATUS_FAILURE_TIMEOUT (API 34); spelled out so it compiles against any SDK. */
    const val STATUS_FAILURE_TIMEOUT = 8
}
