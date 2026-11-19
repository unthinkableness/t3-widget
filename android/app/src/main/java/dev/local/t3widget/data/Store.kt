package dev.local.t3widget.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "t3widget")

/** Connection bookkeeping that isn't a user setting. */
data class ConnectionCache(
    val environmentId: String? = null,
    /** "WIFI:LAN,OTHER:TUNNEL": endpoint that last worked per network class. */
    val remembered: String = "",
    val linkStateCheckedAt: Long = 0,
    /** Set when link-state answered 403 (token has no relay:read); retried after a day. */
    val relayScopeMissingAt: Long = 0,
    /** Last reachability.status from link-state, for the settings screen. */
    val tunnelStatus: String? = null,
) {
    fun rememberedFor(net: NetClass): EndpointKind? = remembered.split(',')
        .firstOrNull { it.startsWith("${net.name}:") }
        ?.substringAfter(':')?.let { s -> EndpointKind.entries.firstOrNull { it.name == s } }

    fun remember(net: NetClass, kind: EndpointKind): ConnectionCache {
        val others = remembered.split(',').filter { it.isNotBlank() && !it.startsWith("${net.name}:") }
        return copy(remembered = (others + "${net.name}:${kind.name}").joinToString(","))
    }
}

/** Settings, the last widget state and connection cache, in one Preferences DataStore. Token is Keystore-encrypted. */
class Store(context: Context) {
    private val ds = context.applicationContext.dataStore

    val settings: Flow<Settings> = ds.data
        .onStart { migrateToken() }
        .map { p ->
            Settings(
                serverUrl = p[SERVER_URL].orEmpty(),
                lanUrl = p[LAN_URL].orEmpty(),
                token = p[TOKEN_ENC]?.let { TokenVault.decrypt(it) } ?: p[TOKEN].orEmpty(),
                useFakeData = p[USE_FAKE] ?: true,
                liveUpdates = p[LIVE] ?: true,
            )
        }
        .distinctUntilChanged()

    val state: Flow<WidgetState> = ds.data.map { WidgetState.fromJson(it[STATE]) }

    suspend fun settings(): Settings = settings.first()
    suspend fun state(): WidgetState = state.first()

    suspend fun saveSettings(s: Settings) {
        val enc = TokenVault.encrypt(s.token.trim())
        ds.edit {
            it[SERVER_URL] = s.serverUrl.trim().trimEnd('/')
            it[LAN_URL] = s.lanUrl.trim().trimEnd('/')
            it[TOKEN_ENC] = enc
            it.remove(TOKEN)
            it[USE_FAKE] = s.useFakeData
            it[LIVE] = s.liveUpdates
        }
    }

    suspend fun setServerUrl(url: String) {
        ds.edit { it[SERVER_URL] = url }
    }

    /** Different server/token: forget the cached environment and remembered endpoints. */
    suspend fun resetConnection() {
        ds.edit { p -> listOf(ENV_ID, REMEMBERED, LINK_CHECKED, RELAY_MISSING, TUNNEL_STATUS).forEach { p.remove(it) } }
    }

    suspend fun saveState(s: WidgetState) {
        ds.edit { it[STATE] = s.toJson() }
    }

    /** Atomic read-modify-write of the state (DataStore serializes edits). */
    suspend fun updateState(f: (WidgetState) -> WidgetState): WidgetState {
        var out = WidgetState.EMPTY
        ds.edit {
            out = f(WidgetState.fromJson(it[STATE]))
            it[STATE] = out.toJson()
        }
        return out
    }

    suspend fun connectionCache(): ConnectionCache = ds.data.first().let { p ->
        ConnectionCache(
            environmentId = p[ENV_ID],
            remembered = p[REMEMBERED].orEmpty(),
            linkStateCheckedAt = p[LINK_CHECKED] ?: 0,
            relayScopeMissingAt = p[RELAY_MISSING] ?: 0,
            tunnelStatus = p[TUNNEL_STATUS],
        )
    }

    suspend fun saveConnection(c: ConnectionCache) {
        ds.edit { p ->
            if (c.environmentId != null) p[ENV_ID] = c.environmentId else p.remove(ENV_ID)
            p[REMEMBERED] = c.remembered
            p[LINK_CHECKED] = c.linkStateCheckedAt
            p[RELAY_MISSING] = c.relayScopeMissingAt
            if (c.tunnelStatus != null) p[TUNNEL_STATUS] = c.tunnelStatus else p.remove(TUNNEL_STATUS)
        }
    }

    /** One-time move of a plain-text token (v0.1) into Keystore-encrypted storage. */
    private suspend fun migrateToken() {
        val plain = ds.data.first()[TOKEN] ?: return
        val enc = TokenVault.encrypt(plain.trim())
        ds.edit {
            it[TOKEN_ENC] = enc
            it.remove(TOKEN)
        }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val LAN_URL = stringPreferencesKey("lan_url")
        /** Legacy plain-text token; migrated away on first read. */
        val TOKEN = stringPreferencesKey("token")
        val TOKEN_ENC = stringPreferencesKey("token_enc")
        val USE_FAKE = booleanPreferencesKey("use_fake")
        val LIVE = booleanPreferencesKey("live_updates")
        val STATE = stringPreferencesKey("widget_state")
        val ENV_ID = stringPreferencesKey("environment_id")
        val REMEMBERED = stringPreferencesKey("remembered_endpoints")
        val LINK_CHECKED = longPreferencesKey("link_state_checked_at")
        val RELAY_MISSING = longPreferencesKey("relay_scope_missing_at")
        val TUNNEL_STATUS = stringPreferencesKey("tunnel_status")
    }
}
