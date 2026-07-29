package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val OFFLINE_CLIENT = TableClient("https://files.example.test", "test-key")

private fun target(id: String, size: Long = 1000L) =
    DownloadTarget(id, "$id.bin", size, "a".repeat(64))

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {

    @Test
    fun `a download walks queued to running to verifying to done`() = runTest {
        var report: ((Long) -> Unit)? = null
        val released = CompletableDeferred<DownloadResult>()
        val queue = queue { _, _, onProgress ->
            report = onProgress
            released.await()
        }

        queue.enqueue(target("one"))
        assertEquals(TransferState.QUEUED, queue.state("one"))

        runCurrent()
        assertEquals(TransferState.RUNNING, queue.state("one"))

        report!!(400L)
        assertEquals(TransferState.RUNNING, queue.state("one"))
        assertEquals(400L, queue.transfers.value.single().bytesDone)

        // Every byte is on disk, but verify → ack → publish still have to happen.
        report!!(1000L)
        assertEquals(TransferState.VERIFYING, queue.state("one"))

        released.complete(DownloadResult.Published("one.bin"))
        runCurrent()
        assertEquals(TransferState.DONE, queue.state("one"))
        assertEquals("one.bin", queue.transfers.value.single().publishedName)
    }

    @Test
    fun `no more than the concurrency cap run at once`() = runTest {
        val released = CompletableDeferred<DownloadResult>()
        val queue = queue(concurrency = 2) { _, _, _ -> released.await() }

        listOf("a", "b", "c").forEach { queue.enqueue(target(it)) }
        runCurrent()

        assertEquals(2, queue.transfers.value.count { it.state == TransferState.RUNNING })
        assertEquals(1, queue.transfers.value.count { it.state == TransferState.QUEUED })

        released.complete(DownloadResult.Published("done"))
        runCurrent()
        assertTrue(queue.transfers.value.all { it.state == TransferState.DONE })
    }

    @Test
    fun `enqueueing something already running changes nothing`() = runTest {
        val released = CompletableDeferred<DownloadResult>()
        var attempts = 0
        val queue = queue { _, _, _ -> attempts++; released.await() }

        queue.enqueue(target("one"))
        runCurrent()
        queue.enqueue(target("one"))
        runCurrent()

        assertEquals(1, attempts)
        assertEquals(1, queue.transfers.value.size)

        released.complete(DownloadResult.Published("one.bin"))
        runCurrent()
    }

    @Test
    fun `a retry clears the failure and runs again`() = runTest {
        val results = ArrayDeque(
            listOf(
                DownloadResult.Failed(TransferFailure("socket closed", retryable = true)),
                DownloadResult.Published("one.bin"),
            )
        )
        val queue = queue { _, _, _ -> results.removeFirst() }

        queue.enqueue(target("one"))
        runCurrent()
        assertEquals(TransferState.FAILED, queue.state("one"))
        assertEquals("socket closed", queue.transfers.value.single().failure?.message)

        queue.retry("one")
        runCurrent()
        assertEquals(TransferState.DONE, queue.state("one"))
        assertNull(queue.transfers.value.single().failure)
    }

    @Test
    fun `an unconfigured server fails the transfer permanently rather than throwing`() = runTest {
        val queue = queue(client = { null }) { _, _, _ -> DownloadResult.Published("never") }

        queue.enqueue(target("one"))
        runCurrent()

        val failure = queue.transfers.value.single().failure
        assertEquals(false, failure?.retryable)
    }

    @Test
    fun `forgetting a transfer drops it from the queue`() = runTest {
        val queue = queue { _, _, _ -> DownloadResult.Published("one.bin") }

        queue.enqueue(target("one"))
        runCurrent()
        queue.forget("one")

        assertTrue(queue.transfers.value.isEmpty())
    }

    private fun TestScope.queue(
        concurrency: Int = 2,
        client: suspend () -> TableClient? = { OFFLINE_CLIENT },
        download: suspend (TableClient, DownloadTarget, (Long) -> Unit) -> DownloadResult,
    ) = DownloadQueue(backgroundScope, client, download, concurrency)

    private fun DownloadQueue.state(id: String): TransferState? =
        transfers.value.firstOrNull { it.id == id }?.state
}
