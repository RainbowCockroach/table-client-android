package com.rainbowcockroach.table.tableandroidclient.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainbowcockroach.table.tableandroidclient.AppContainer
import com.rainbowcockroach.table.tableandroidclient.settings.TableSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ConnectionTest {
    data object Running : ConnectionTest
    data class Reachable(val fileCount: Int) : ConnectionTest
    data class Unreachable(val message: String) : ConnectionTest
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val testState = MutableStateFlow<ConnectionTest?>(null)
    val connectionTest: StateFlow<ConnectionTest?> = testState.asStateFlow()

    val settings: StateFlow<TableSettings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun save(settings: TableSettings) {
        val wifiOnlyChanged = settings.uploadOnWifiOnly != this.settings.value?.uploadOnWifiOnly
        testState.value = null
        viewModelScope.launch {
            container.settings.save(settings)
            if (wifiOnlyChanged) container.transfers.applyUploadPolicy()
        }
    }

    /** DESIGN §5: `GET /files` is the cheapest proof that the host, TLS, and key all work. */
    fun testConnection(settings: TableSettings) {
        testState.value = ConnectionTest.Running
        viewModelScope.launch {
            testState.value = withContext(Dispatchers.IO) {
                runCatching { container.clientFor(settings).listFiles() }.fold(
                    onSuccess = { ConnectionTest.Reachable(it.size) },
                    onFailure = { ConnectionTest.Unreachable(it.message ?: it.toString()) },
                )
            }
        }
    }
}
