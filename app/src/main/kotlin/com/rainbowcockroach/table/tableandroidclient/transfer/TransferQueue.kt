package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.TableFile
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * What the user does to the queue: add files, retry, dismiss.
 *
 * Every change lands in the [TransferStore] before the [TransferScheduler] hears about it,
 * so a worker started by the scheduler always finds its record.
 */
class TransferQueue(
    private val store: TransferStore,
    private val scheduler: TransferScheduler,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val transfers: Flow<List<TransferRecord>> = store.transfers

    /** No-op while [file] is already downloading; a finished or failed entry starts over. */
    suspend fun download(file: TableFile) {
        val existing = store.all().firstOrNull { it.remoteId == file.id && it.direction == TransferDirection.DOWNLOAD }
        if (existing != null && !existing.isFinished) return
        existing?.let { dismiss(it.id) }
        add(
            TransferRecord(
                id = newId(),
                direction = TransferDirection.DOWNLOAD,
                name = file.name,
                size = file.size,
                remoteId = file.id,
                sha256 = file.sha256,
            )
        )
    }

    suspend fun upload(sourceUri: String, name: String, size: Long) {
        add(
            TransferRecord(
                id = newId(),
                direction = TransferDirection.UPLOAD,
                name = name,
                size = size,
                sourceUri = sourceUri,
            )
        )
    }

    suspend fun retry(id: String) {
        val record = store.update(id) {
            it.copy(state = TransferState.QUEUED, failure = null)
        } ?: return
        scheduler.runNow(record.id)
    }

    suspend fun dismiss(id: String) {
        scheduler.cancel(id)
        store.delete(id)
    }

    /**
     * Rule 14: process death or reboot leaves records behind that WorkManager may not still
     * have work for. Scheduling with the keep-existing policy re-arms those without
     * disturbing work that survived.
     */
    suspend fun resumeUnfinished() {
        store.all()
            .filterNot { it.state == TransferState.DONE || it.failure?.retryable == false }
            .forEach { scheduler.schedule(it.id) }
    }

    private suspend fun add(record: TransferRecord) {
        store.put(record.copy(createdAt = now()))
        scheduler.schedule(record.id)
    }
}
