package com.rainbowcockroach.table.tableandroidclient.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

private val Context.preferences: DataStore<Preferences> by preferencesDataStore(name = "table-settings")

private val HOST_URL = stringPreferencesKey("host_url")
private val ALLOW_INSECURE_HTTP = booleanPreferencesKey("allow_insecure_http")
private val UPLOAD_ON_WIFI_ONLY = booleanPreferencesKey("upload_on_wifi_only")

/**
 * DESIGN §4: the API key lives in EncryptedSharedPreferences, everything else in DataStore.
 *
 * [settings] emits on every change from either half.
 */
class SettingsStore(context: Context) {

    private val preferences = context.applicationContext.preferences
    private val apiKeys = ApiKeyStore(context.applicationContext)

    // EncryptedSharedPreferences has no Flow of its own; this mirrors it for the UI.
    private val apiKey = MutableStateFlow<String?>(null)

    val settings: Flow<TableSettings> = flow {
        if (apiKey.value == null) apiKey.value = withContext(Dispatchers.IO) { apiKeys.read() }
        emitAll(
            combine(preferences.data, apiKey.filterNotNull()) { stored, key ->
                TableSettings(
                    hostUrl = stored[HOST_URL].orEmpty(),
                    apiKey = key,
                    allowInsecureHttp = stored[ALLOW_INSECURE_HTTP] ?: false,
                    uploadOnWifiOnly = stored[UPLOAD_ON_WIFI_ONLY] ?: false,
                )
            }
        )
    }

    suspend fun save(settings: TableSettings) {
        preferences.edit {
            it[HOST_URL] = settings.hostUrl.trim()
            it[ALLOW_INSECURE_HTTP] = settings.allowInsecureHttp
            it[UPLOAD_ON_WIFI_ONLY] = settings.uploadOnWifiOnly
        }
        setApiKey(settings.apiKey)
    }

    private suspend fun setApiKey(value: String) {
        val trimmed = value.trim()
        withContext(Dispatchers.IO) { apiKeys.write(trimmed) }
        apiKey.value = trimmed
    }
}
