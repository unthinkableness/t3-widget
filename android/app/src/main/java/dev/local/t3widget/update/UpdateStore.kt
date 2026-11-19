package dev.local.t3widget.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** What the settings screen shows about the last check. */
enum class CheckOutcome {
    NEVER, UP_TO_DATE, NO_RELEASES, AVAILABLE, DOWNLOADING, READY, INSTALLING, INSTALLED, NETWORK_ERROR, ERROR;

    companion object {
        fun parse(s: String?): CheckOutcome = entries.firstOrNull { it.name == s } ?: NEVER
    }
}

/** Updater bookkeeping, persisted as one JSON value (atomic read-modify-write). */
data class UpdateState(
    val lastCheckAt: Long = 0,
    val outcome: CheckOutcome = CheckOutcome.NEVER,
    /** Human-readable last result ("Up to date", "Install failed: …"). */
    val message: String? = null,
    /** Newest release seen (versionCode 0 = none). */
    val latestCode: Int = 0,
    val latestName: String? = null,
    val latestNotes: String? = null,
    /** A downloaded APK whose SHA-256 matched version.json. */
    val readyCode: Int = 0,
    val readyPath: String? = null,
    /** Downloaded and waiting for the user (install confirmation or "Install unknown apps" permission). */
    val awaitingUser: Boolean = false,
    /** [awaitingUser] because "Install unknown apps" isn't granted yet (continue when it is). */
    val needsPermission: Boolean = false,
    /** Set when a session was committed, to report success after the app restarts as the new version. */
    val installingCode: Int = 0,
    /** A version whose install failed or was declined: automatic checks don't retry it, the button does. */
    val failedCode: Int = 0,
    /** Version already announced with an "Update available" notification (auto-install off). */
    val notifiedCode: Int = 0,
    /** Consecutive network failures, and when automatic checks may run again. */
    val failures: Int = 0,
    val nextAutoCheckAt: Long = 0,
    /** ETag of the last releases/latest response, and what it pointed at. */
    val etag: String? = null,
    val release: String? = null,
    /** The version.json fetched for [release], so a 304 needs no second request. */
    val versionJson: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("lastCheckAt", lastCheckAt)
        put("outcome", outcome.name)
        putOpt("message", message)
        put("latestCode", latestCode)
        putOpt("latestName", latestName)
        putOpt("latestNotes", latestNotes)
        put("readyCode", readyCode)
        putOpt("readyPath", readyPath)
        put("awaitingUser", awaitingUser)
        put("needsPermission", needsPermission)
        put("installingCode", installingCode)
        put("failedCode", failedCode)
        put("notifiedCode", notifiedCode)
        put("failures", failures)
        put("nextAutoCheckAt", nextAutoCheckAt)
        putOpt("etag", etag)
        putOpt("release", release)
        putOpt("versionJson", versionJson)
    }.toString()

    companion object {
        fun fromJson(s: String?): UpdateState {
            if (s.isNullOrBlank()) return UpdateState()
            return runCatching {
                val o = JSONObject(s)
                fun str(k: String) = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
                UpdateState(
                    lastCheckAt = o.optLong("lastCheckAt"),
                    outcome = CheckOutcome.parse(str("outcome")),
                    message = str("message"),
                    latestCode = o.optInt("latestCode"),
                    latestName = str("latestName"),
                    latestNotes = str("latestNotes"),
                    readyCode = o.optInt("readyCode"),
                    readyPath = str("readyPath"),
                    awaitingUser = o.optBoolean("awaitingUser"),
                    needsPermission = o.optBoolean("needsPermission"),
                    installingCode = o.optInt("installingCode"),
                    failedCode = o.optInt("failedCode"),
                    notifiedCode = o.optInt("notifiedCode"),
                    failures = o.optInt("failures"),
                    nextAutoCheckAt = o.optLong("nextAutoCheckAt"),
                    etag = str("etag"),
                    release = str("release"),
                    versionJson = str("versionJson"),
                )
            }.getOrDefault(UpdateState())
        }
    }
}

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "t3widget_updates")

/** Separate DataStore so updater writes never contend with the widget/live-service state. */
class UpdateStore(context: Context) {
    private val ds = context.applicationContext.updateDataStore

    val state: Flow<UpdateState> = ds.data.map { UpdateState.fromJson(it[STATE]) }.distinctUntilChanged()
    val autoInstall: Flow<Boolean> = ds.data.map { it[AUTO_INSTALL] ?: true }.distinctUntilChanged()

    suspend fun state(): UpdateState = state.first()
    suspend fun autoInstall(): Boolean = autoInstall.first()

    suspend fun setAutoInstall(on: Boolean) {
        ds.edit { it[AUTO_INSTALL] = on }
    }

    suspend fun update(f: (UpdateState) -> UpdateState): UpdateState {
        var out = UpdateState()
        ds.edit {
            out = f(UpdateState.fromJson(it[STATE]))
            it[STATE] = out.toJson()
        }
        return out
    }

    private companion object {
        val STATE = stringPreferencesKey("update_state")
        val AUTO_INSTALL = booleanPreferencesKey("auto_install")
    }
}
