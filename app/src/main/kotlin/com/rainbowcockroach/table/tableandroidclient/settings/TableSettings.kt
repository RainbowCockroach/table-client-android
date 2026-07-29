package com.rainbowcockroach.table.tableandroidclient.settings

/** Everything needed to reach a server. [apiKey] is read back from EncryptedSharedPreferences. */
data class TableSettings(
    val hostUrl: String = "",
    val apiKey: String = "",
    val allowInsecureHttp: Boolean = false,
) {
    val isConfigured: Boolean get() = hostUrl.isNotBlank() && apiKey.isNotBlank()
}
