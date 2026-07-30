package com.rainbowcockroach.table.tableandroidclient.testsupport

import com.rainbowcockroach.table.tableandroidclient.transfer.TransferRecord
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferScheduler
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/** Stands in for the Room store: same ordering, same read-modify-write atomicity. */
class InMemoryTransferStore : TransferStore {

    private val rows = MutableStateFlow<Map<String, TransferRecord>>(emptyMap())
    private val writes = Mutex()

    override val transfers: Flow<List<TransferRecord>> = rows.map { it.ordered() }

    override suspend fun all(): List<TransferRecord> = rows.value.ordered()

    override suspend fun get(id: String): TransferRecord? = rows.value[id]

    override suspend fun put(record: TransferRecord) = writes.withLock {
        rows.value += record.id to record
    }

    override suspend fun update(
        id: String,
        change: (TransferRecord) -> TransferRecord,
    ): TransferRecord? = writes.withLock {
        val current = rows.value[id] ?: return null
        change(current).also { rows.value += id to it }
    }

    override suspend fun delete(id: String) = writes.withLock {
        rows.value -= id
    }

    private fun Map<String, TransferRecord>.ordered() = values.sortedWith(compareBy({ it.createdAt }, { it.id }))
}

class RecordingScheduler : TransferScheduler {

    val scheduled: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val ranNow: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The network constraint each id was last enqueued with. */
    val unmetered: MutableMap<String, Boolean> = Collections.synchronizedMap(mutableMapOf())

    override fun schedule(id: String, unmetered: Boolean) {
        scheduled += id
        this.unmetered[id] = unmetered
    }

    override fun runNow(id: String, unmetered: Boolean) {
        ranNow += id
        this.unmetered[id] = unmetered
    }

    override fun cancel(id: String) {
        cancelled += id
    }
}
