package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.FileState
import com.rainbowcockroach.table.tableandroidclient.api.TableFile
import com.rainbowcockroach.table.tableandroidclient.testsupport.InMemoryTransferStore
import com.rainbowcockroach.table.tableandroidclient.testsupport.RecordingScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferQueueTest {

    private val store = InMemoryTransferStore()
    private val scheduler = RecordingScheduler()
    private var nextId = 0
    private val queue = queue()

    @Test
    fun `a queued download carries what the worker needs to run it`() = runTest {
        queue.download(file("abc"))

        val record = store.all().single()
        assertEquals(TransferDirection.DOWNLOAD, record.direction)
        assertEquals("abc", record.remoteId)
        assertEquals("abc.bin", record.name)
        assertEquals(HASH, record.sha256)
        assertEquals(TransferState.QUEUED, record.state)
        assertEquals(listOf(record.id), scheduler.scheduled)
    }

    @Test
    fun `the same file is not queued twice while it is still running`() = runTest {
        queue.download(file("abc"))
        store.update(store.all().single().id) { it.copy(state = TransferState.RUNNING) }
        queue.download(file("abc"))

        assertEquals(1, store.all().size)
        assertEquals(1, scheduler.scheduled.size)
    }

    @Test
    fun `a finished download can be asked for again`() = runTest {
        queue.download(file("abc"))
        val first = store.all().single().id
        store.update(first) { it.copy(state = TransferState.DONE) }

        queue.download(file("abc"))

        val record = store.all().single()
        assertEquals(TransferState.QUEUED, record.state)
        assertTrue(first !in store.all().map { it.id }, "the finished record is replaced, not kept")
        assertEquals(listOf(first), scheduler.cancelled)
    }

    @Test
    fun `an upload keeps its source so a later attempt can re-read it`() = runTest {
        queue.upload("content://docs/7", "holiday.jpg", 4096)

        val record = store.all().single()
        assertEquals(TransferDirection.UPLOAD, record.direction)
        assertEquals("content://docs/7", record.sourceUri)
        assertEquals(4096L, record.size)
        assertNull(record.remoteId, "the session only exists once the server has been asked")
    }

    @Test
    fun `retry clears the failure and runs the work now`() = runTest {
        queue.download(file("abc"))
        val id = store.all().single().id
        store.update(id) {
            it.copy(state = TransferState.FAILED, failure = TransferFailure("socket closed", retryable = true))
        }

        queue.retry(id)

        val record = store.all().single()
        assertEquals(TransferState.QUEUED, record.state)
        assertNull(record.failure)
        assertEquals(listOf(id), scheduler.ranNow)
    }

    @Test
    fun `dismissing cancels the work before the record is gone`() = runTest {
        queue.download(file("abc"))
        val id = store.all().single().id

        queue.dismiss(id)

        assertTrue(store.all().isEmpty())
        assertEquals(listOf(id), scheduler.cancelled)
    }

    /** Rule 14: what a killed process left behind is what has to be scheduled again. */
    @Test
    fun `resuming schedules the unfinished and leaves the settled alone`() = runTest {
        val unfinished = record("running", TransferState.RUNNING)
        val retrying = record("retrying", TransferState.FAILED, TransferFailure("dropped", retryable = true))
        val dead = record("dead", TransferState.FAILED, TransferFailure("gone", retryable = false))
        val done = record("done", TransferState.DONE)
        listOf(unfinished, retrying, dead, done).forEach { store.put(it) }
        scheduler.scheduled.clear()

        queue.resumeUnfinished()

        assertEquals(setOf("running", "retrying"), scheduler.scheduled.toSet())
    }

    /** DESIGN §6: the Wi-Fi-only setting is about uploads; a download is never held back. */
    @Test
    fun `an upload waits for an unmetered network when the setting asks for it`() = runTest {
        val onWifi = queue(uploadOnWifiOnly = true)
        onWifi.upload("content://docs/7", "holiday.jpg", 4096)
        onWifi.download(file("abc"))

        assertEquals(true, scheduler.unmetered[idOf(TransferDirection.UPLOAD)])
        assertEquals(false, scheduler.unmetered[idOf(TransferDirection.DOWNLOAD)])
    }

    @Test
    fun `turning the setting on re-enqueues the uploads that are still going`() = runTest {
        val running = upload("running", TransferState.RUNNING)
        val retrying = upload("retrying", TransferState.FAILED, TransferFailure("dropped", retryable = true))
        val dead = upload("dead", TransferState.FAILED, TransferFailure("gone", retryable = false))
        val sent = upload("sent", TransferState.DONE)
        val downloading = record("downloading", TransferState.RUNNING)
        listOf(running, retrying, dead, sent, downloading).forEach { store.put(it) }

        queue(uploadOnWifiOnly = true).applyUploadPolicy()

        assertEquals(setOf("running", "retrying"), scheduler.ranNow.toSet())
        assertEquals(mapOf("running" to true, "retrying" to true), scheduler.unmetered)
        // Waiting for Wi-Fi is not running: a row still saying RUNNING would claim otherwise.
        assertEquals(TransferState.QUEUED, store.get("running")!!.state)
    }

    @Test
    fun `dismissing an upload drops the copy that was staged for it`() = runTest {
        val staged = RecordingStagedUploads()
        val withStaging = queue(staging = staged)
        withStaging.upload("file:/data/staged/1.upload", "shared.bin", 8)
        val id = store.all().single().id

        withStaging.dismiss(id)

        assertEquals(listOf("file:/data/staged/1.upload"), staged.discarded)
    }

    private fun queue(uploadOnWifiOnly: Boolean = false, staging: StagedUploads = StagedUploads.None) =
        TransferQueue(
            store = store,
            scheduler = scheduler,
            uploadOnWifiOnly = { uploadOnWifiOnly },
            staging = staging,
            now = { 1_000L },
            newId = { "local-${nextId++}" },
        )

    private suspend fun idOf(direction: TransferDirection) =
        store.all().single { it.direction == direction }.id

    private fun record(id: String, state: TransferState, failure: TransferFailure? = null) = TransferRecord(
        id = id,
        direction = TransferDirection.DOWNLOAD,
        name = "$id.bin",
        size = 10,
        state = state,
        remoteId = id,
        sha256 = HASH,
        failure = failure,
    )

    private fun upload(id: String, state: TransferState, failure: TransferFailure? = null) =
        record(id, state, failure).copy(
            direction = TransferDirection.UPLOAD,
            remoteId = null,
            sourceUri = "content://docs/$id",
        )
}

private class RecordingStagedUploads : StagedUploads {

    val discarded = mutableListOf<String>()

    override fun discard(record: TransferRecord) {
        record.sourceUri?.let { discarded += it }
    }

    override fun sweep(live: List<TransferRecord>) = Unit
}

private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private fun file(id: String) = TableFile(
    id = id,
    name = "$id.bin",
    size = 1000,
    sha256 = HASH,
    state = FileState.AVAILABLE,
    bytesReceived = 1000,
)
