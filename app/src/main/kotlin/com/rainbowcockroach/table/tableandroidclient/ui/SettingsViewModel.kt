package com.rainbowcockroach.table.tableandroidclient.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainbowcockroach.table.tableandroidclient.AppContainer
import com.rainbowcockroach.table.tableandroidclient.settings.TableSettings
import com.rainbowcockroach.table.tableandroidclient.update.RELEASES_PAGE_URL
import com.rainbowcockroach.table.tableandroidclient.update.UpdateStatus
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

    private val updateState = MutableStateFlow<UpdateStatus?>(null)
    val updateCheck: StateFlow<UpdateStatus?> = updateState.asStateFlow()

    val appVersion: String = container.updates.installedVersion

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

    fun checkForUpdate() {
        updateState.value = UpdateStatus.Checking
        viewModelScope.launch {
            updateState.value = withContext(Dispatchers.IO) { container.updates.check() }
        }
    }

    /** Clears the prompt: the user has either declined the update or left for the browser. */
    fun dismissUpdate() {
        updateState.value = null
    }

    /** Nothing on the device took the URL, so the prompt has to say so rather than vanish. */
    fun updateOpenFailed() {
        updateState.value = UpdateStatus.Failed("no app on this device can open $RELEASES_PAGE_URL")
    }
}
