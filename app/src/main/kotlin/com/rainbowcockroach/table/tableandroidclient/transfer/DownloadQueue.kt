package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** DESIGN §3. */
private const val MAX_CONCURRENT_DOWNLOADS = 2

private val FINISHED_STATES = setOf(TransferState.DONE, TransferState.FAILED)

/**
 * The live download queue: what to fetch, in what state, at most [concurrency] at a time.
 *
 * Retry is a user action rather than automatic backoff — [download] is one attempt, and
 * scheduling belongs to WorkManager from C4 on.
 */
class DownloadQueue(
    private val scope: CoroutineScope,
    private val clientFor: suspend () -> TableClient?,
    private val download: suspend (TableClient, DownloadTarget, (Long) -> Unit) -> DownloadResult,
    concurrency: Int = MAX_CONCURRENT_DOWNLOADS,
) {
    private val slots = Semaphore(concurrency)
    private val state = MutableStateFlow<List<Transfer>>(emptyList())

    val transfers: StateFlow<List<Transfer>> = state.asStateFlow()

    /** No-op while [target] is already downloading; a finished or failed entry starts over. */
    fun enqueue(target: DownloadTarget) {
        val existing = state.value.firstOrNull { it.id == target.id }
        if (existing != null && existing.state !in FINISHED_STATES) return
        put(Transfer(target, TransferState.QUEUED))
        scope.launch { runQueued(target) }
    }

    fun retry(id: String) {
        val transfer = state.value.firstOrNull { it.id == id } ?: return
        enqueue(transfer.target)
    }

    fun forget(id: String) = state.update { transfers -> transfers.filterNot { it.id == id } }

    private suspend fun runQueued(target: DownloadTarget) = slots.withPermit {
        // Building a client rejects a bad host URL and, per rule 13, a plain http:// one.
        val client = runCatching { clientFor() }
            .getOrElse { return@withPermit failPermanently(target.id, it.message ?: it.toString()) }
            ?: return@withPermit failPermanently(target.id, "no server configured")
        update(target.id) { it.copy(state = TransferState.RUNNING) }
        finish(target.id, download(client, target) { bytesOnDisk -> onProgress(target, bytesOnDisk) })
    }

    private fun onProgress(target: DownloadTarget, bytesOnDisk: Long) = update(target.id) {
        // Every byte is on disk but the transfer is not done: hashing and acking are what is left.
        val phase = if (bytesOnDisk >= target.size) TransferState.VERIFYING else TransferState.RUNNING
        it.copy(state = phase, bytesDone = bytesOnDisk)
    }

    private fun failPermanently(id: String, message: String) =
        finish(id, DownloadResult.Failed(TransferFailure(message, retryable = false)))

    private fun finish(id: String, result: DownloadResult) = update(id) {
        when (result) {
            is DownloadResult.Published -> it.copy(
                state = TransferState.DONE,
                bytesDone = it.size,
                failure = null,
                publishedName = result.publishedName,
            )

            is DownloadResult.Failed -> it.copy(state = TransferState.FAILED, failure = result.failure)
        }
    }

    private fun put(transfer: Transfer) = state.update { transfers ->
        transfers.filterNot { it.id == transfer.id } + transfer
    }

    private fun update(id: String, change: (Transfer) -> Transfer) = state.update { transfers ->
        transfers.map { if (it.id == id) change(it) else it }
    }
}
