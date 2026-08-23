package com.rainbowcockroach.table.tableandroidclient.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainbowcockroach.table.tableandroidclient.AppContainer
import com.rainbowcockroach.table.tableandroidclient.api.TableFile
import com.rainbowcockroach.table.tableandroidclient.settings.TableSettings
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    private val noticeState = MutableStateFlow<String?>(null)

    /** The transient line above the list: what the picker never says, and a reveal that failed. */
    val notice: StateFlow<String?> = noticeState.asStateFlow()

    private val goneState = MutableStateFlow(emptySet<String>())

    /** Ids of landed downloads whose published copy is no longer there (`../UI.md` §5). */
    val goneDownloads: StateFlow<Set<String>> = goneState.asStateFlow()

    val transfers: StateFlow<List<TransferRecord>> = container.transfers.transfers
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Null until the stored settings are in — "no server yet" must not flash before then. */
    val settings: StateFlow<TableSettings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Bytes-done updates churn the record list; only the landed URIs are worth re-checking.
        viewModelScope.launch {
            transfers.map(::landedCopies).distinctUntilChanged().collect(::checkPublishedCopies)
        }
    }

    suspend fun refresh() {
        // A file manager can delete a taken file at any time, so this rides the list poll.
        checkPublishedCopies(landedCopies(transfers.value))
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

    fun download(file: TableFile) = viewModelScope.launch { container.transfers.download(file) }

    /** DESIGN §5's "download all": the queue drops the ones already running (rule 15 included). */
    fun downloadAll() = viewModelScope.launch {
        listState.value.files.forEach { container.transfers.download(it) }
    }

    fun upload(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        val intake = withContext(Dispatchers.IO) { container.uploads.accept(uris) }
        noticeState.value = intakeProblem(intake)
    }

    fun dismissNotice() {
        noticeState.value = null
    }

    fun reportRevealFailed() {
        noticeState.value = "Couldn't open the Downloads folder."
    }

    fun reportOpenFailed() {
        noticeState.value = "Couldn't open that file."
    }

    private suspend fun checkPublishedCopies(landed: List<Pair<String, String>>) {
        goneState.value = withContext(Dispatchers.IO) {
            landed.filterNot { (_, uri) -> container.publishedDownloads.exists(uri) }
                .map { (id, _) -> id }
                .toSet()
        }
    }

    fun retry(transferId: String) = viewModelScope.launch { container.transfers.retry(transferId) }

    fun dismiss(transferId: String) = viewModelScope.launch { dismissOne(transferId) }

    /** The whole settled tail at once, so a long queue need not be dismissed row by row. */
    fun dismissFinished() = viewModelScope.launch {
        transfers.value.filter { it.isFinished }.forEach { dismissOne(it.id) }
    }

    private suspend fun dismissOne(transferId: String) {
        container.notifications.cancelSettled(transferId)
        container.transfers.dismiss(transferId)
    }
}

/** Transfer id to the `content://` URI it was published as, for the rows that have one. */
private fun landedCopies(records: List<TransferRecord>): List<Pair<String, String>> =
    records.mapNotNull { record -> record.publishedUri?.let { record.id to it } }
