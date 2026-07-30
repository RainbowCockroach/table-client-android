package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.testsupport.InMemoryTransferStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val OFFLINE_CLIENT = TableClient("https://files.example.test", "test-key")
private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

class TransferRunnerTest {

    private val store = InMemoryTransferStore()

    @Test
    fun `a download walks running to verifying to done`() = runTest {
        val seen = mutableListOf<TransferState>()
        store.put(download())
        val runner = runner { _, record, report ->
            seen += stateOf(record.id)
            report.bytes(400)
            seen += stateOf(record.id)
            report.bytes(1000)
            seen += stateOf(record.id)
            TransferResult.Done("one.bin")
        }

        assertEquals(RunOutcome.DONE, runner.run("one"))

        assertEquals(
            listOf(TransferState.RUNNING, TransferState.RUNNING, TransferState.VERIFYING),
            seen,
        )
        val record = store.get("one")!!
        assertEquals(TransferState.DONE, record.state)
        assertEquals(1000L, record.bytesDone)
        assertEquals("one.bin", record.publishedName)
        assertNull(record.failure)
    }

    @Test
    fun `a retryable failure keeps the record and asks WorkManager for another go`() = runTest {
        store.put(download())
        val runner = runner { _, _, report ->
            report.bytes(600)
            retryable("socket closed")
        }

        assertEquals(RunOutcome.RETRY, runner.run("one", runAttempt = 0))

        val record = store.get("one")!!
        assertEquals(TransferState.FAILED, record.state)
        assertEquals(600L, record.bytesDone, "the next attempt resumes from what got through")
        assertTrue(record.failure!!.retryable)
    }

    @Test
    fun `the last allowed attempt turns a retryable failure into a dead one`() = runTest {
        store.put(download())
        val runner = runner(maxAttempts = 3) { _, _, _ -> retryable("socket closed") }

        assertEquals(RunOutcome.GAVE_UP, runner.run("one", runAttempt = 2))

        val failure = store.get("one")!!.failure!!
        assertFalse(failure.retryable)
        assertTrue("gave up after 3 attempts" in failure.message, failure.message)
    }

    @Test
    fun `an attempt that throws is a verdict, not a crash`() = runTest {
        store.put(download())
        val runner = runner { _, _, _ -> throw IllegalStateException("download one has no file id") }

        assertEquals(RunOutcome.GAVE_UP, runner.run("one"))
        assertEquals("download one has no file id", store.get("one")!!.failure!!.message)
    }

    @Test
    fun `an unconfigured server fails the transfer permanently rather than throwing`() = runTest {
        store.put(download())
        var attempted = false
        val runner = TransferRunner(store, { null }, { _, _, _ -> attempted = true; TransferResult.Done() })

        assertEquals(RunOutcome.GAVE_UP, runner.run("one"))

        assertFalse(attempted)
        assertEquals("no server configured", store.get("one")!!.failure!!.message)
    }

    @Test
    fun `a transfer dismissed before its worker started is simply not run`() = runTest {
        var attempted = false
        val runner = runner { _, _, _ -> attempted = true; TransferResult.Done() }

        assertEquals(RunOutcome.DONE, runner.run("gone"))
        assertFalse(attempted)
    }

    /** Rules 2 and 14: the session id has to outlive the process that created it. */
    @Test
    fun `an upload session is durable before any bytes are sent`() = runTest {
        store.put(upload())
        var whenBytesStarted: TransferRecord? = null
        val runner = runner { _, record, report ->
            report.session(UploadSession("session-1", "holiday.jpg", 4096, HASH))
            report.bytes(0)
            whenBytesStarted = runBlocking { store.get(record.id) }
            TransferResult.Done()
        }

        runner.run("two")

        assertEquals("session-1", whenBytesStarted?.remoteId)
        assertEquals(HASH, whenBytesStarted?.sha256)
        assertEquals(4096L, whenBytesStarted?.size)
    }

    /** Rule 3: a rejected finalize drops the session so the retry declares the file afresh. */
    @Test
    fun `a lost upload session is cleared from the record`() = runTest {
        store.put(upload().copy(remoteId = "session-1", sha256 = HASH))
        val runner = runner { _, _, report ->
            report.session(null)
            retryable("the server rejected the upload")
        }

        runner.run("two")

        assertNull(store.get("two")!!.remoteId)
    }

    @Test
    fun `no more than the concurrency cap run at once`() = runBlocking(Dispatchers.Default) {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val release = CountDownLatch(1)
        listOf("a", "b", "c", "d").forEach { store.put(download(it)) }
        val runner = runner(concurrency = 2) { _, _, _ ->
            peak.accumulateAndGet(running.incrementAndGet()) { seen, now -> maxOf(seen, now) }
            release.await(5, TimeUnit.SECONDS)
            running.decrementAndGet()
            TransferResult.Done()
        }

        val runs = listOf("a", "b", "c", "d").map { async { runner.run(it) } }
        awaitUntil { running.get() == 2 }
        // Long enough that a third attempt would have shown up by now.
        Thread.sleep(200)
        assertEquals(2, peak.get())

        release.countDown()
        assertTrue(runs.awaitAll().all { it == RunOutcome.DONE })
    }

    private fun runner(
        maxAttempts: Int = MAX_ATTEMPTS,
        concurrency: Int = 2,
        attempt: TransferAttempt,
    ) = TransferRunner(
        store = store,
        clientFor = { OFFLINE_CLIENT },
        attempt = attempt,
        maxAttempts = maxAttempts,
        concurrency = concurrency,
    )

    private fun stateOf(id: String) = runBlocking { store.get(id)!!.state }

    private fun download(id: String = "one") = TransferRecord(
        id = id,
        direction = TransferDirection.DOWNLOAD,
        name = "$id.bin",
        size = 1000,
        remoteId = "file-$id",
        sha256 = HASH,
    )

    private fun upload() = TransferRecord(
        id = "two",
        direction = TransferDirection.UPLOAD,
        name = "holiday.jpg",
        size = 4096,
        sourceUri = "content://docs/7",
    )
}

private fun awaitUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "condition never held" }
        Thread.sleep(10)
    }
}
