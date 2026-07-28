package com.rainbowcockroach.table.tableandroidclient

import com.rainbowcockroach.table.tableandroidclient.api.AckResult
import com.rainbowcockroach.table.tableandroidclient.api.AppendResult
import com.rainbowcockroach.table.tableandroidclient.api.FileState
import com.rainbowcockroach.table.tableandroidclient.api.RangeNotSatisfiableException
import com.rainbowcockroach.table.tableandroidclient.api.TableAuthException
import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.api.TableFile
import com.rainbowcockroach.table.tableandroidclient.crypto.TRANSFER_BUFFER_BYTES
import com.rainbowcockroach.table.tableandroidclient.crypto.sha256Hex
import com.rainbowcockroach.table.tableandroidclient.testsupport.FailingUploadSource
import com.rainbowcockroach.table.tableandroidclient.testsupport.RequestLog
import com.rainbowcockroach.table.tableandroidclient.testsupport.TestServer
import com.rainbowcockroach.table.tableandroidclient.testsupport.randomFile
import com.rainbowcockroach.table.tableandroidclient.transfer.DownloadOutcome
import com.rainbowcockroach.table.tableandroidclient.transfer.DownloadTarget
import com.rainbowcockroach.table.tableandroidclient.transfer.Downloader
import com.rainbowcockroach.table.tableandroidclient.transfer.FileUploadSource
import com.rainbowcockroach.table.tableandroidclient.transfer.UploadOutcome
import com.rainbowcockroach.table.tableandroidclient.transfer.UploadSource
import com.rainbowcockroach.table.tableandroidclient.transfer.Uploader
import com.rainbowcockroach.table.tableandroidclient.transfer.skipFully
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MIB = 1024 * 1024
private val BOGUS_SHA = "0".repeat(64)

/**
 * The server's conformance scenarios re-run through this client's real code paths
 * (DESIGN §7). Each test maps to one script in `table-server/conformance/scenarios/`.
 *
 * Fault-injected drops (scenario 10) are checkpoint C2 and are not covered here.
 */
class ConformanceTest {

    @get:Rule
    val scratch = TemporaryFolder()

    private val log = RequestLog()
    private lateinit var client: TableClient
    private lateinit var uploader: Uploader
    private lateinit var downloader: Downloader

    @Before
    fun connectToServer() {
        val candidate = TestServer.clientOrNull(log)
        assumeTrue(TestServer.missingConfigMessage, candidate != null)
        val connected = candidate!!
        try {
            connected.listFiles()
        } catch (io: IOException) {
            assumeTrue(TestServer.unreachableMessage(io), false)
        }
        client = connected
        uploader = Uploader(client)
        downloader = Downloader(client)
        sweepServer()
        log.clear()
    }

    /** Scenarios are independent, exactly as `conformance/run.sh` keeps them. */
    @After
    fun sweepServer() {
        if (::client.isInitialized) client.listFiles().forEach { client.deleteFile(it.id) }
    }

    // --- 01_auth: the bearer key gates everything (conformance rule 12) ---------------

    @Test
    fun `scenario 01 - a wrong api key is rejected and the right one is not`() {
        val wrongKey = TableClient(TestServer.url!!, "definitely-wrong", allowInsecureHttp = true)
        assertFailsWith<TableAuthException> { wrongKey.listFiles() }

        client.listFiles()
    }

    // --- 02_roundtrip: upload → listed → download → verify → ack → gone --------------

    @Test
    fun `scenario 02 - a file survives a round trip byte-identical and then disappears`() {
        val source = randomFile(scratch.root, "roundtrip.bin", MIB)
        val sourceHash = sha256Hex(source)
        val uploaded = uploadFully(source)

        val listed = assertNotNull(client.listFiles().find { it.id == uploaded.id })
        assertEquals(FileState.AVAILABLE, listed.state)
        assertEquals(sourceHash, listed.sha256)
        assertEquals(listed.size, listed.bytesReceived)
        assertNotNull(listed.expiresAt, "an available file has a running TTL")

        val temp = scratch.newFile("roundtrip.part")
        val verified = assertIs<DownloadOutcome.Verified>(downloader.download(DownloadTarget(listed), temp))
        assertEquals(AckResult.DELETED, verified.ack)
        assertEquals(sourceHash, verified.sha256)
        assertContentEquals(source.readBytes(), temp.readBytes())

        assertTrue(client.listFiles().none { it.id == uploaded.id }, "acked file is still listed")
        assertEquals(
            DownloadOutcome.Gone,
            downloader.download(DownloadTarget(listed), scratch.newFile("roundtrip.again")),
        )
    }

    // --- 03_upload_resume: resume from the server's offset, never from zero (rule 2) --

    @Test
    fun `scenario 03 - a half-sent upload resumes from the committed offset`() {
        val source = randomFile(scratch.root, "resume.bin", 2 * MIB)
        val sourceHash = sha256Hex(source)
        val half = source.length() / 2
        val session = uploader.createSession(FileUploadSource(source))

        assertEquals(AppendResult.Incomplete(half), appendRange(session.id, source, 0, half))
        assertEquals(half, client.uploadOffset(session.id))

        // A short body, so the 409 never races an unread request body on the socket.
        val replayed = source.inputStream().use { client.appendBytes(session.id, 0, it, 1024) }
        assertEquals(
            AppendResult.OffsetMismatch(half), replayed,
            "a replay from a stale offset must be refused, not silently accepted",
        )

        assertResumesFrom(half) { uploader.upload(session, FileUploadSource(source)) }
        assertEquals(sourceHash, downloadVerified(session.id, source.length(), sourceHash).sha256)
    }

    /**
     * DESIGN §6: the source stream itself can die mid-upload. Whatever the server managed
     * to commit, the retry continues from there rather than starting over.
     *
     * A locally aborted connection resets rather than draining, so how much lands is up to
     * the kernel; scenario 10 (checkpoint C2) is where the drop offset is exact.
     */
    @Test
    fun `an upload whose source dies mid-stream resumes from the server's offset`() {
        val source = randomFile(scratch.root, "dying-source.bin", 2 * MIB)
        val sourceHash = sha256Hex(source)
        val session = uploader.createSession(FileUploadSource(source))

        assertFailsWith<IOException> {
            uploader.upload(session, FailingUploadSource(FileUploadSource(source), 700_000))
        }

        val committed = assertNotNull(client.uploadOffset(session.id), "the session must outlive the failure")
        assertTrue(committed < source.length(), "a failed upload must not finalize")

        assertResumesFrom(committed) { uploader.upload(session, FileUploadSource(source)) }
        assertEquals(sourceHash, downloadVerified(session.id, source.length(), sourceHash).sha256)
    }

    // --- 04_upload_hash_mismatch: a corrupt upload never becomes visible (rule 3) ----

    @Test
    fun `scenario 04 - a declared hash that does not match destroys the session`() {
        val source = randomFile(scratch.root, "liar.bin", 512 * 1024)
        val lyingSession = client.createUpload("liar.bin", source.length(), BOGUS_SHA)

        val result = source.inputStream().use {
            client.appendBytes(lyingSession, 0, it, source.length())
        }
        assertIs<AppendResult.FinalizeRejected>(result)

        assertNull(client.uploadOffset(lyingSession), "the rejected session must be destroyed")
        assertTrue(client.listFiles().none { it.id == lyingSession }, "rejected upload is listed")

        // Rule 3: the retry is a brand-new session, and it succeeds.
        val honest = uploadFully(source)
        assertTrue(client.listFiles().any { it.id == honest.id })
    }

    // --- 05_download_range_resume: resume from the partial temp file (rule 5) --------

    @Test
    fun `scenario 05 - an interrupted download resumes with Range and verifies whole`() {
        val source = randomFile(scratch.root, "ranged.bin", MIB)
        val sourceHash = sha256Hex(source)
        val uploaded = uploadFully(source)

        val cut = 500_000L
        val temp = scratch.newFile("ranged.part")
        streamPrefixTo(temp, uploaded.id, cut)
        assertEquals(cut, temp.length())

        assertFailsWith<RangeNotSatisfiableException> {
            client.openDownload(uploaded.id, rangeFrom = 10L * MIB)
        }

        log.clear()
        var lowestProgress = Long.MAX_VALUE
        val outcome = downloader.download(DownloadTarget(uploaded), temp) {
            lowestProgress = minOf(lowestProgress, it)
        }

        val verified = assertIs<DownloadOutcome.Verified>(outcome)
        assertEquals(AckResult.DELETED, verified.ack)
        assertEquals(sourceHash, verified.sha256)
        assertContentEquals(source.readBytes(), temp.readBytes())
        assertTrue(lowestProgress > cut, "the resumed download restarted at $lowestProgress")

        val resumeGet = assertNotNull(log.of("GET").firstOrNull())
        assertEquals("bytes=$cut-", resumeGet.range, "rule 5: Range comes from the partial file's size")
        assertEquals(206, resumeGet.status)
    }

    // --- 06_ack_semantics: ack is hash-gated and 404 means success (rules 8, 9, 10) --

    @Test
    fun `scenario 06 - ack deletes only on a matching hash and repeats are success`() {
        val source = randomFile(scratch.root, "acked.bin", 256 * 1024)
        val sourceHash = sha256Hex(source)
        val uploaded = uploadFully(source)

        assertEquals(AckResult.HASH_MISMATCH, client.ack(uploaded.id, BOGUS_SHA))
        assertTrue(client.listFiles().any { it.id == uploaded.id }, "a bad ack must not delete")

        assertEquals(AckResult.DELETED, client.ack(uploaded.id, sourceHash))
        assertEquals(AckResult.ALREADY_GONE, client.ack(uploaded.id, sourceHash))
    }

    @Test
    fun `scenario 06 - a corrupt local copy is discarded instead of acked`() {
        val source = randomFile(scratch.root, "corrupt.bin", 128 * 1024)
        val uploaded = uploadFully(source)

        // Right length, wrong bytes: nothing is left to fetch, so only verification can catch it.
        val temp = scratch.newFile("corrupt.part")
        temp.writeBytes(ByteArray(source.length().toInt()))
        log.clear()

        assertIs<DownloadOutcome.Corrupt>(downloader.download(DownloadTarget(uploaded), temp))

        assertFalse(temp.exists(), "rule 10: a corrupt local copy is discarded")
        assertTrue(client.listFiles().any { it.id == uploaded.id }, "the server copy must survive")
        assertTrue(
            log.entries.none { it.path.endsWith("/ack") },
            "rule 8: never ack a copy that failed verification",
        )
    }

    // --- 07_ttl_expiry: unacked files vanish on their own ----------------------------

    @Test
    fun `scenario 07 - an unacked file expires`() {
        val ttl = TestServer.ttlSeconds
        assumeTrue(
            "TABLE_TTL_SECONDS unset or too long — run the dev server with a short TABLE_TTL",
            ttl != null && ttl <= 60,
        )
        val source = randomFile(scratch.root, "doomed.bin", 64 * 1024)
        val uploaded = uploadFully(source)
        assertTrue(client.listFiles().any { it.id == uploaded.id })

        // The sweeper runs every TABLE_SWEEP_INTERVAL, 15 s by default.
        Thread.sleep((ttl!! + 20) * 1000)

        assertTrue(client.listFiles().none { it.id == uploaded.id }, "expired file is still listed")
        assertEquals(
            DownloadOutcome.Gone,
            downloader.download(DownloadTarget(uploaded), scratch.newFile("doomed.part")),
        )
    }

    // --- 08_live_relay: uploading files are listed and downloadable (rule 15) --------

    @Test
    fun `scenario 08 - a download started mid-upload tail-follows to a verified file`() {
        val source = randomFile(scratch.root, "live.bin", 2 * MIB)
        val sourceHash = sha256Hex(source)
        val half = source.length() / 2
        val session = uploader.createSession(FileUploadSource(source))

        assertIs<AppendResult.Incomplete>(appendRange(session.id, source, 0, half))

        val listed = assertNotNull(client.listFiles().find { it.id == session.id })
        assertEquals(FileState.UPLOADING, listed.state)
        assertEquals(half, listed.bytesReceived)
        assertNull(listed.expiresAt, "the TTL starts at finalize, not at upload")

        val temp = scratch.newFile("live.part")
        val pool = Executors.newSingleThreadExecutor()
        try {
            val download = pool.submit<DownloadOutcome> { downloader.download(DownloadTarget(listed), temp) }
            Thread.sleep(500)
            assertIs<AppendResult.Finalized>(appendRange(session.id, source, half, source.length()))

            val verified = assertIs<DownloadOutcome.Verified>(download.get(60, TimeUnit.SECONDS))
            assertEquals(AckResult.DELETED, verified.ack)
            assertEquals(sourceHash, verified.sha256)
            assertContentEquals(source.readBytes(), temp.readBytes())
        } finally {
            pool.shutdownNow()
        }
    }

    // --- 09_concurrent_downloads: both complete, the second ack is success (rule 9) --

    @Test
    fun `scenario 09 - two concurrent downloads both verify and the loser acks as gone`() {
        val source = randomFile(scratch.root, "popular.bin", MIB)
        val sourceHash = sha256Hex(source)
        val uploaded = uploadFully(source)
        val target = DownloadTarget(uploaded)
        val temps = listOf(scratch.newFile("popular.a"), scratch.newFile("popular.b"))

        val pool = Executors.newFixedThreadPool(temps.size)
        try {
            val both = temps
                .map { temp -> pool.submit<DownloadOutcome> { downloader.download(target, temp) } }
                .map { assertIs<DownloadOutcome.Verified>(it.get(60, TimeUnit.SECONDS)) }

            both.forEach { assertEquals(sourceHash, it.sha256) }
            temps.forEach { assertContentEquals(source.readBytes(), it.readBytes()) }
            assertEquals(
                listOf(AckResult.DELETED, AckResult.ALREADY_GONE), both.map { it.ack }.sorted(),
                "exactly one ack deletes; the other is told the file is already gone",
            )
        } finally {
            pool.shutdownNow()
        }
    }

    // --- helpers --------------------------------------------------------------------

    private fun uploadFully(source: File): TableFile {
        val uploadSource: UploadSource = FileUploadSource(source)
        val session = uploader.createSession(uploadSource)
        return assertIs<UploadOutcome.Finalized>(uploader.upload(session, uploadSource)).file
    }

    /** Conformance rule 2: the offset comes from `HEAD`, and the upload never restarts at zero. */
    private fun assertResumesFrom(committed: Long, upload: () -> UploadOutcome) {
        log.clear()
        assertIs<UploadOutcome.Finalized>(upload())

        assertEquals("HEAD", log.entries.first().method, "rule 2: ask the server where it got to")
        val resumePatch = assertNotNull(log.of("PATCH").lastOrNull())
        assertEquals(committed.toString(), resumePatch.uploadOffset, "rule 2: resume, never restart")
    }

    private fun appendRange(id: String, source: File, from: Long, to: Long): AppendResult =
        source.inputStream().use { stream ->
            stream.skipFully(from)
            client.appendBytes(id, from, stream, to - from)
        }

    /** Reads only the first [byteCount] bytes and hangs up — a client-side mid-download drop. */
    private fun streamPrefixTo(temp: File, id: String, byteCount: Long) {
        client.openDownload(id).use { stream ->
            temp.outputStream().use { sink ->
                val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                var written = 0L
                while (written < byteCount) {
                    val wanted = minOf(buffer.size.toLong(), byteCount - written).toInt()
                    val read = stream.source.read(buffer, 0, wanted)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    written += read
                }
            }
        }
    }

    private fun downloadVerified(id: String, size: Long, sha256: String): DownloadOutcome.Verified {
        val target = DownloadTarget(id, "verify.bin", size, sha256)
        return assertIs(downloader.download(target, scratch.newFile("verify.$id")))
    }
}
