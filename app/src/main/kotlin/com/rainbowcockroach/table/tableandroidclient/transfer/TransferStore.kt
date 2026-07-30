package com.rainbowcockroach.table.tableandroidclient.transfer

import kotlinx.coroutines.flow.Flow

/**
 * Where the queue lives across process death (conformance rule 14).
 *
 * An interface so the queue, the runner and their tests are plain Kotlin; the durable
 * implementation is [RoomTransferStore].
 */
interface TransferStore {
    /** Oldest first, so the UI order is the order things were queued in. */
    val transfers: Flow<List<TransferRecord>>

    suspend fun all(): List<TransferRecord>

    suspend fun get(id: String): TransferRecord?

    suspend fun put(record: TransferRecord)

    /** Read-modify-write as one step; null if the record was dismissed meanwhile. */
    suspend fun update(id: String, change: (TransferRecord) -> TransferRecord): TransferRecord?

    suspend fun delete(id: String)
}
