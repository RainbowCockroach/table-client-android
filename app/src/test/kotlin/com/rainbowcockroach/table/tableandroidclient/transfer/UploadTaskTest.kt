package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.crypto.sha256Hex
import com.rainbowcockroach.table.tableandroidclient.testsupport.FaultInjector
import com.rainbowcockroach.table.tableandroidclient.testsupport.InMemoryTransferStore
import com.rainbowcockroach.table.tableandroidclient.testsupport.RequestLog
import com.rainbowcockroach.table.tableandroidclient.testsupport.TestServer
import com.rainbowcockroach.table.tableandroidclient.testsupport.randomFile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FILE_SIZE = 1024 * 1024
private const val DROP_AFTER = 300_000L

/**
 * The upload half of the checklist, driven through the queue's runner against a real server
 * (DESIGN §7): rule 1's declare-before-you-send, rule 2's resume-from-the-server's-offset,
 * and rule 3's start-over-with-a-new-session.
 */
class UploadTaskTest {

    @get:Rule
    val scratch = TemporaryFolder()

    private val log = RequestLog()
    private val faults = FaultInjector()
    private val store = InMemoryTransferStore()

    private lateinit var client: TableClient
    private lateinit var runner: TransferRunner

    @Before
    fun connectToServer() {
        val candidate = TestServer.clientOrNull(log, faults)
        assumeTrue(TestServer.missingConfigMessage, candidate != null)
        client = candidate!!
        try {
            client.listFiles()
        } catch (io: IOException) {
            assumeTrue(TestServer.unreachableMessage(io), false)
        }
        runner = TransferRunner(
            store = store,
            clientFor = { client },
            attempt = TransferTasks(
                downloads = DownloadTask(scratch.newFolder("temp"), NeverPublishes),
                uploads = UploadTask(FileSources),
            ),
        )
        sweepServer()
        log.clear()
    }

    @After
    fun sweepServer() {
        if (::client.isInitialized) client.listFiles().forEach { client.deleteFile(it.id) }
    }

    @Test
    fun `an upload declares what it is sending and finalizes`() = runTest {
        val source = randomFile(scratch.root, "sent.bin", FILE_SIZE)
        queue(source)

        assertEquals(RunOutcome.DONE, runner.run("upload"))

        val record = store.get("upload")!!
        assertEquals(TransferState.DONE, record.state)
        val onServer = client.listFiles().single()
        assertEquals("sent.bin", onServer.name)
        assertEquals(sha256Hex(source), onServer.sha256)
        assertEquals(source.length(), onServer.size)
        // The id the session was created with is the file's id for its whole lifecycle.
        assertEquals(onServer.id, record.remoteId)
    }

    /** Conformance rule 2, from the exact byte the server committed. */
    @Test
    fun `a PATCH dropped mid-body resumes from the committed offset`() = runTest {
        assumeTrue(TestServer.faultsDisabledMessage, TestServer.faultsEnabled)
        val source = randomFile(scratch.root, "resumed.bin", FILE_SIZE)
        queue(source)

        faults.dropAfter("PATCH", DROP_AFTER)
        assertEquals(RunOutcome.RETRY, runner.run("upload"))
        val afterDrop = store.get("upload")!!
        assertTrue(afterDrop.failure!!.retryable)
        assertNotNull(afterDrop.remoteId, "the session id has to survive for the retry to resume")

        log.clear()
        assertEquals(RunOutcome.DONE, runner.run("upload", runAttempt = 1))

        assertEquals("HEAD", log.entries.first().method, "rule 2: ask the server where it got to")
        assertEquals(
            DROP_AFTER.toString(), log.of("PATCH").single().uploadOffset,
            "rule 2: resume, never restart",
        )
        assertTrue(log.of("POST").isEmpty(), "the session survived the drop and is reused")
        assertEquals(afterDrop.remoteId, store.get("upload")!!.remoteId)
        assertEquals(sha256Hex(source), client.listFiles().single().sha256)
    }

    @Test
    fun `a session the server has forgotten is replaced rather than resumed`() = runTest {
        val source = randomFile(scratch.root, "forgotten.bin", 64 * 1024)
        queue(source, sessionId = "00000000-0000-4000-8000-000000000000", sha256 = sha256Hex(source))

        assertEquals(RunOutcome.RETRY, runner.run("upload"))
        assertNull(store.get("upload")!!.remoteId, "a dead session must not be resumed forever")

        assertEquals(RunOutcome.DONE, runner.run("upload", runAttempt = 1))
        assertEquals(sha256Hex(source), client.listFiles().single().sha256)
    }

    /** Conformance rule 3: the server discarded the session, so only a new one can work. */
    @Test
    fun `a finalize the server rejects starts over with a new session`() = runTest {
        val source = randomFile(scratch.root, "misdeclared.bin", 64 * 1024)
        val lie = "f".repeat(64)
        val misdeclared = client.createUpload("misdeclared.bin", source.length(), lie)
        queue(source, sessionId = misdeclared, sha256 = lie)

        assertEquals(RunOutcome.RETRY, runner.run("upload"))
        val afterRejection = store.get("upload")!!
        assertNull(afterRejection.remoteId)
        assertTrue("rejected" in afterRejection.failure!!.message, afterRejection.failure!!.message)

        assertEquals(RunOutcome.DONE, runner.run("upload", runAttempt = 1))
        val onServer = client.listFiles().single()
        assertEquals(sha256Hex(source), onServer.sha256)
        assertTrue(onServer.id != misdeclared, "the rejected session cannot be reused")
    }

    @Test
    fun `a source that can no longer be read fails permanently`() = runTest {
        val source = randomFile(scratch.root, "vanished.bin", 1024)
        queue(source)
        source.delete()

        assertEquals(RunOutcome.GAVE_UP, runner.run("upload"))

        val failure = store.get("upload")!!.failure!!
        assertTrue("vanished.bin again" in failure.message, failure.message)
        assertTrue(client.listFiles().isEmpty())
    }

    private suspend fun queue(source: File, sessionId: String? = null, sha256: String? = null) = store.put(
        TransferRecord(
            id = "upload",
            direction = TransferDirection.UPLOAD,
            name = source.name,
            size = source.length(),
            sourceUri = source.path,
            remoteId = sessionId,
            sha256 = sha256,
        )
    )
}

/** The queue stores a `content://` URI on Android; on the JVM a path is the same contract. */
private object FileSources : UploadSources {
    override fun open(record: TransferRecord): UploadSource {
        val file = File(checkNotNull(record.sourceUri))
        if (!file.exists()) throw SourceUnavailableException("${file.name} is gone")
        return FileUploadSource(file, record.name)
    }
}

private object NeverPublishes : DownloadPublisher {
    override fun publish(source: File, displayName: String): String =
        throw UnsupportedOperationException("this suite only uploads")
}
