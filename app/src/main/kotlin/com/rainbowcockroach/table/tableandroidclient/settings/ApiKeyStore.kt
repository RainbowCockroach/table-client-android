package com.rainbowcockroach.table.tableandroidclient.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val FILE_NAME = "table-secrets"
private const val KEY_API_KEY = "api_key"

/**
 * The API key's only storage. Conformance rule 12 keeps it out of DataStore, logs and backups.
 *
 * Opening the file derives a keystore-backed master key, so it happens off the main thread
 * on first use; [SettingsStore] owns that dispatching.
 */
// security-crypto 1.1.0 deprecated these along with Tink's Android integration without
// shipping a replacement; DESIGN §4 names EncryptedSharedPreferences, so it stays until
// the spec picks a successor.
@Suppress("DEPRECATION")
internal class ApiKeyStore(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun read(): String = prefs.getString(KEY_API_KEY, null).orEmpty()

    fun write(apiKey: String) = prefs.edit().putString(KEY_API_KEY, apiKey).apply()
}
