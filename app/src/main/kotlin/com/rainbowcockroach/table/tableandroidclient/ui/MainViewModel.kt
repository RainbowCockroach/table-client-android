package com.rainbowcockroach.table.tableandroidclient.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainbowcockroach.table.tableandroidclient.AppContainer
import com.rainbowcockroach.table.tableandroidclient.api.TableFile
import com.rainbowcockroach.table.tableandroidclient.settings.TableSettings
import com.rainbowcockroach.table.tableandroidclient.transfer.DownloadTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** DESIGN §5: the list refreshes on this cadence while the screen is in the foreground. */
const val LIST_POLL_MILLIS = 5_000L

data class FileListState(
    val files: List<TableFile> = emptyList(),
    val error: String? = null,
    val loaded: Boolean = false,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val listState = MutableStateFlow(FileListState())
    val files: StateFlow<FileListState> = listState.asStateFlow()

    val transfers = container.downloads.transfers

    /** Null until the stored settings are in — "no server yet" must not flash before then. */
    val settings: StateFlow<TableSettings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    suspend fun refresh() {
        val current = settings.value
        if (current == null || !current.isConfigured) {
            listState.value = FileListState(error = null, loaded = false)
            return
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { container.clientFor(current).listFiles() }
        }
        listState.update { previous ->
            result.fold(
                onSuccess = { FileListState(files = it, loaded = true) },
                // Keep the last good list on screen; a poll failing is not the same as an empty table.
                onFailure = { previous.copy(error = it.message ?: it.toString()) },
            )
        }
    }

    fun download(file: TableFile) = container.downloads.enqueue(DownloadTarget(file))

    fun retry(transferId: String) = container.downloads.retry(transferId)

    fun dismiss(transferId: String) = container.downloads.forget(transferId)
}
