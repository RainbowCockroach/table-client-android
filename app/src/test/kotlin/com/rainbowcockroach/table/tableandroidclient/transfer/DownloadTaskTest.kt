package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.crypto.sha256Hex
import com.rainbowcockroach.table.tableandroidclient.testsupport.TestServer
import com.rainbowcockroach.table.tableandroidclient.testsupport.randomFile
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The publish half of conformance rule 11, against a real server (DESIGN §7).
 *
 * MediaStore itself is verified by hand per release; what these tests pin down is the
 * ordering around it — nothing is published before the ack, and nothing is lost after it.
 */
class DownloadTaskTest {

    @get:Rule
    val scratch = TemporaryFolder()

    private lateinit var client: TableClient
    private lateinit var uploader: Uploader
    private lateinit var publisher: RecordingPublisher
    private lateinit var tempDir: File

    @Before
    fun connectToServer() {
        val candidate = TestServer.clientOrNull()
        assumeTrue(TestServer.missingConfigMessage, candidate != null)
        client = candidate!!
        try {
            client.listFiles()
        } catch (io: IOException) {
            assumeTrue(TestServer.unreachableMessage(io), false)
        }
        uploader = Uploader(client)
        tempDir = scratch.newFolder("temp")
        publisher = RecordingPublisher(scratch.newFolder("downloads"))
        sweepServer()
    }

    @After
    fun sweepServer() {
        if (::client.isInitialized) client.listFiles().forEach { client.deleteFile(it.id) }
    }

    @Test
    fun `a verified download is published and the temp file is dropped`() {
        val source = randomFile(scratch.root, "published.bin", 512 * 1024)
        val target = upload(source)

        val result = assertIs<TransferResult.Done>(task().run(client, target))

        assertEquals("published.bin", result.published?.name)
        assertContentEquals(source.readBytes(), publisher.file("published.bin").readBytes())
        assertFalse(tempFileFor(target).exists(), "the temp file outlives the ack but not the publish")
        assertTrue(client.listFiles().none { it.id == target.id }, "the acked file is still on the server")
    }

    /**
     * Rule 11: the ack has already deleted the server's copy, so the verified temp file is
     * the only one left and must survive a failed publish for the retry to use.
     */
    @Test
    fun `a failed publish keeps the verified copy and the retry publishes it`() {
        val source = randomFile(scratch.root, "stubborn.bin", 256 * 1024)
        val target = upload(source)

        publisher.failNextPublish = true
        val failed = assertIs<TransferResult.Failed>(task().run(client, target))
        assertTrue(failed.failure.retryable)

        val kept = tempFileFor(target)
        assertEquals(source.length(), kept.length(), "the verified copy must not be discarded")
        assertTrue(client.listFiles().none { it.id == target.id }, "the ack already happened")

        // Rule 9: the second ack is answered 404, which is success, so the retry still publishes.
        val retried = assertIs<TransferResult.Done>(task().run(client, target))
        assertEquals("stubborn.bin", retried.published?.name)
        assertContentEquals(source.readBytes(), publisher.file("stubborn.bin").readBytes())
        assertFalse(kept.exists())
    }

    @Test
    fun `a name that already exists in Downloads gets a suffix instead of overwriting`() {
        val existing = publisher.file("twice.bin").apply { writeBytes(ByteArray(4)) }
        val source = randomFile(scratch.root, "twice.bin", 64 * 1024)
        val target = upload(source)

        val result = assertIs<TransferResult.Done>(task().run(client, target))

        assertEquals("twice (1).bin", result.published?.name)
        assertEquals(4, existing.length(), "the file already in Downloads must not be touched")
    }

    @Test
    fun `a file the server no longer has fails permanently`() {
        val source = randomFile(scratch.root, "vanished.bin", 64 * 1024)
        val target = upload(source)
        client.deleteFile(target.id)

        val failed = assertIs<TransferResult.Failed>(task().run(client, target))

        assertFalse(failed.failure.retryable, "re-requesting a deleted file will never work")
        assertTrue(publisher.published.isEmpty())
    }

    private fun task() = DownloadTask(tempDir, publisher)

    private fun tempFileFor(target: DownloadTarget) = File(tempDir, "${target.id}.part")

    private fun upload(source: File): DownloadTarget {
        val uploadSource = FileUploadSource(source)
        val session = uploader.createSession(uploadSource)
        val finalized = assertIs<UploadOutcome.Finalized>(uploader.upload(session, uploadSource))
        assertEquals(sha256Hex(source), finalized.file.sha256)
        return DownloadTarget(finalized.file)
    }
}

/** Stands in for MediaStore: same collision rules, somewhere a JVM test can look. */
private class RecordingPublisher(private val directory: File) : DownloadPublisher {

    var failNextPublish = false
    val published = mutableListOf<String>()

    fun file(name: String) = File(directory, name)

    override fun publish(source: File, displayName: String): PublishedDownload {
        if (failNextPublish) {
            failNextPublish = false
            throw IOException("simulated publish failure")
        }
        val name = uniqueDisplayName(safeDisplayName(displayName)) { file(it).exists() }
        source.copyTo(file(name))
        published += name
        return PublishedDownload(name, file(name).toURI().toString())
    }
}
