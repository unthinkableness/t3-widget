package dev.local.t3widget

import android.content.pm.PackageInstaller
import dev.local.t3widget.update.CheckOutcome
import dev.local.t3widget.update.UpdateCore
import dev.local.t3widget.update.UpdateState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateCoreTest {

    // sha256("abc")
    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun asset(name: String, url: String) = JSONObject().put("name", name).put("browser_download_url", url)

    private fun release(vararg assets: JSONObject, tag: String = "v0.4.0") = JSONObject()
        .put("tag_name", tag)
        .put("draft", false)
        .put("assets", JSONArray().apply { assets.forEach { put(it) } })
        .toString()

    private inline fun expectParseError(block: () -> Unit) {
        try {
            block()
            fail("expected ParseException")
        } catch (e: UpdateCore.ParseException) {
            // ok
        }
    }

    // ---- version comparison

    @Test fun onlyStrictlyNewerVersionsAreUpdates() {
        assertTrue(UpdateCore.isNewer(4, 3))
        assertFalse(UpdateCore.isNewer(3, 3))
        assertFalse(UpdateCore.isNewer(2, 3))
        assertFalse(UpdateCore.isNewer(0, 3))
    }

    // ---- releases/latest

    @Test fun parsesReleaseAssets() {
        val r = UpdateCore.parseLatestRelease(
            release(
                asset("other.txt", "https://example.com/other.txt"),
                asset("t3-widget.apk", "https://github.com/unthinkableness/t3-widget/releases/download/v0.4.0/t3-widget.apk"),
                asset("version.json", "https://github.com/unthinkableness/t3-widget/releases/download/v0.4.0/version.json"),
            )
        )
        assertEquals("v0.4.0", r.tag)
        assertTrue(r.apkUrl.endsWith("/t3-widget.apk"))
        assertTrue(r.versionUrl.endsWith("/version.json"))
        assertEquals(r, UpdateCore.Release.fromJson(r.toJson()))
    }

    @Test fun releaseWithoutVersionJsonOrApkIsRejected() {
        expectParseError { UpdateCore.parseLatestRelease(release(asset("t3-widget.apk", "https://x/t3-widget.apk"))) }
        expectParseError { UpdateCore.parseLatestRelease(release(asset("version.json", "https://x/version.json"))) }
        expectParseError { UpdateCore.parseLatestRelease(release()) }
        expectParseError { UpdateCore.parseLatestRelease("""{"message":"Not Found"}""") }
        expectParseError { UpdateCore.parseLatestRelease("<html>") }
    }

    @Test fun nonHttpsAssetUrlsAreIgnored() {
        expectParseError {
            UpdateCore.parseLatestRelease(
                release(asset("t3-widget.apk", "http://x/t3-widget.apk"), asset("version.json", "https://x/version.json"))
            )
        }
    }

    @Test fun parsesRealGithubShape() {
        // Trimmed shape of a real api.github.com releases/latest response.
        val json = """
            {"url":"https://api.github.com/repos/o/r/releases/1","tag_name":"v0.4.0","name":"0.4.0","draft":false,
             "prerelease":false,"assets":[
              {"url":"https://api.github.com/repos/o/r/releases/assets/2","name":"t3-widget.apk","content_type":"application/vnd.android.package-archive","size":2000000,
               "browser_download_url":"https://github.com/o/r/releases/download/v0.4.0/t3-widget.apk"},
              {"url":"https://api.github.com/repos/o/r/releases/assets/3","name":"version.json","content_type":"application/json","size":120,
               "browser_download_url":"https://github.com/o/r/releases/download/v0.4.0/version.json"}],
             "body":"notes"}
        """.trimIndent()
        val r = UpdateCore.parseLatestRelease(json)
        assertEquals("https://github.com/o/r/releases/download/v0.4.0/version.json", r.versionUrl)
    }

    // ---- version.json

    @Test fun parsesVersionJson() {
        val v = UpdateCore.parseVersionJson(
            """{"versionCode":4,"versionName":"0.4.0","sha256":"${abcSha.uppercase()}","notes":"short text","minSdk":26}"""
        )
        assertEquals(4, v.versionCode)
        assertEquals("0.4.0", v.versionName)
        assertEquals(abcSha, v.sha256) // normalised to lower case
        assertEquals("short text", v.notes)
        assertEquals(26, v.minSdk)
    }

    @Test fun versionJsonDefaults() {
        val v = UpdateCore.parseVersionJson("""{"versionCode":5,"versionName":"0.5.0","sha256":"$abcSha"}""")
        assertEquals("", v.notes)
        assertEquals(1, v.minSdk)
        assertEquals("", UpdateCore.parseVersionJson("""{"versionCode":5,"versionName":"0.5.0","sha256":"$abcSha","notes":null}""").notes)
    }

    @Test fun invalidVersionJsonIsRejected() {
        expectParseError { UpdateCore.parseVersionJson("""{"versionName":"0.4.0","sha256":"$abcSha"}""") }
        expectParseError { UpdateCore.parseVersionJson("""{"versionCode":0,"versionName":"0.4.0","sha256":"$abcSha"}""") }
        expectParseError { UpdateCore.parseVersionJson("""{"versionCode":4,"sha256":"$abcSha"}""") }
        expectParseError { UpdateCore.parseVersionJson("""{"versionCode":4,"versionName":"0.4.0"}""") }
        expectParseError { UpdateCore.parseVersionJson("""{"versionCode":4,"versionName":"0.4.0","sha256":"abc"}""") }
        expectParseError { UpdateCore.parseVersionJson("""{"versionCode":4,"versionName":"0.4.0","sha256":"${"z".repeat(64)}"}""") }
        expectParseError { UpdateCore.parseVersionJson("not json") }
    }

    // ---- hashing

    @Test fun sha256OfStream() {
        assertEquals(abcSha, UpdateCore.sha256Hex("abc".byteInputStream()))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            UpdateCore.sha256Hex(ByteArray(0).inputStream()),
        )
        // Larger than one read buffer.
        val big = ByteArray(200_000) { (it % 251).toByte() }
        assertEquals(
            UpdateCore.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(big)),
            UpdateCore.sha256Hex(big.inputStream()),
        )
    }

    @Test fun hashCheck() {
        assertTrue(UpdateCore.hashMatches(abcSha, abcSha))
        assertTrue(UpdateCore.hashMatches(abcSha.uppercase(), " $abcSha "))
        assertFalse(UpdateCore.hashMatches(abcSha, abcSha.replaceFirst('b', 'c')))
        assertFalse(UpdateCore.hashMatches(abcSha, null))
        assertFalse(UpdateCore.hashMatches(null, abcSha))
        assertFalse(UpdateCore.hashMatches("", ""))
        assertFalse(UpdateCore.hashMatches("abc", "abc"))
        assertNull(UpdateCore.normalizeSha256("g".repeat(64)))
    }

    // ---- backoff

    @Test fun backoffDoublesAndCaps() {
        assertEquals(0L, UpdateCore.backoffMs(0))
        assertEquals(15 * 60_000L, UpdateCore.backoffMs(1))
        assertEquals(30 * 60_000L, UpdateCore.backoffMs(2))
        assertEquals(60 * 60_000L, UpdateCore.backoffMs(3))
        assertEquals(UpdateCore.MAX_BACKOFF_MS, UpdateCore.backoffMs(6))
        assertEquals(UpdateCore.MAX_BACKOFF_MS, UpdateCore.backoffMs(1000))
    }

    // ---- install status wording

    @Test fun signatureMismatchIsExplained() {
        val m = "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package dev.local.t3widget signatures do not match newer version; ignoring!"
        assertTrue(UpdateCore.isSignatureMismatch(m))
        assertTrue(UpdateCore.describeInstallFailure(PackageInstaller.STATUS_FAILURE_CONFLICT, m).startsWith("Signature mismatch"))
        assertFalse(UpdateCore.isSignatureMismatch("INSTALL_FAILED_INSUFFICIENT_STORAGE"))
        assertFalse(UpdateCore.isSignatureMismatch(null))
    }

    @Test fun installFailuresHaveReasons() {
        assertEquals("Cancelled", UpdateCore.describeInstallFailure(PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertEquals(
            "Not enough storage (INSTALL_FAILED_INSUFFICIENT_STORAGE)",
            UpdateCore.describeInstallFailure(PackageInstaller.STATUS_FAILURE_STORAGE, "INSTALL_FAILED_INSUFFICIENT_STORAGE"),
        )
        assertTrue(UpdateCore.describeInstallFailure(PackageInstaller.STATUS_FAILURE_BLOCKED, "").startsWith("Blocked"))
        assertEquals("Install failed (status 99)", UpdateCore.describeInstallFailure(99, null))
    }

    // ---- persisted state

    @Test fun updateStateRoundTrips() {
        val s = UpdateState(
            lastCheckAt = 123, outcome = CheckOutcome.READY, message = "0.4.0 ready", latestCode = 4,
            latestName = "0.4.0", latestNotes = "notes", readyCode = 4, readyPath = "/cache/updates/t3-widget-4.apk",
            awaitingUser = true, needsPermission = true, installingCode = 4, failedCode = 2, notifiedCode = 4,
            failures = 3, nextAutoCheckAt = 456, etag = "W/\"abc\"", release = "{}", versionJson = "{\"a\":1}",
        )
        assertEquals(s, UpdateState.fromJson(s.toJson()))
        assertEquals(UpdateState(), UpdateState.fromJson(UpdateState().toJson()))
        assertEquals(UpdateState(), UpdateState.fromJson(null))
        assertEquals(UpdateState(), UpdateState.fromJson("garbage"))
    }
}
