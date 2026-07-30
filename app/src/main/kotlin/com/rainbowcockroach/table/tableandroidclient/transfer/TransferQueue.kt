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
    private val uploadOnWifiOnly: suspend () -> Boolean = { false },
    private val staging: StagedUploads = StagedUploads.None,
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
        scheduler.runNow(record.id, unmeteredFor(record))
    }

    suspend fun dismiss(id: String) {
        scheduler.cancel(id)
        val record = store.get(id)
        store.delete(id)
        record?.let(staging::discard)
    }

    /**
     * Rule 14: process death or reboot leaves records behind that WorkManager may not still
     * have work for. Scheduling with the keep-existing policy re-arms those without
     * disturbing work that survived.
     */
    suspend fun resumeUnfinished() {
        val live = unsettled()
        live.forEach { scheduler.schedule(it.id, unmeteredFor(it)) }
        staging.sweep(live)
    }

    /**
     * DESIGN §6: the Wi-Fi-only setting is a WorkManager constraint, so uploads already in the
     * queue obey a change only once they are enqueued again. Each one resumes from the offset
     * the server reports (rule 2), so re-enqueueing costs a round trip and no bytes.
     */
    suspend fun applyUploadPolicy() {
        val unmetered = uploadOnWifiOnly()
        unsettled()
            .filter { it.direction == TransferDirection.UPLOAD }
            .forEach { record ->
                // Back to QUEUED first: the replacement work may sit waiting for Wi-Fi, and a
                // row left saying RUNNING would claim bytes are moving when none are.
                store.update(record.id) { it.copy(state = TransferState.QUEUED) }
                scheduler.runNow(record.id, unmetered)
            }
    }

    /** Neither finished nor beyond hope — the records a scheduler should still know about. */
    private suspend fun unsettled(): List<TransferRecord> = store.all()
        .filterNot { it.state == TransferState.DONE || it.failure?.retryable == false }

    private suspend fun unmeteredFor(record: TransferRecord): Boolean =
        record.direction == TransferDirection.UPLOAD && uploadOnWifiOnly()

    private suspend fun add(record: TransferRecord) {
        store.put(record.copy(createdAt = now()))
        scheduler.schedule(record.id, unmeteredFor(record))
    }
}
