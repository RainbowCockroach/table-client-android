package com.rainbowcockroach.table.tableandroidclient

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.rainbowcockroach.table.tableandroidclient.api.AppendResult
import com.rainbowcockroach.table.tableandroidclient.api.FileState
import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.crypto.sha256Hex
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferDirection
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferRecord
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferState
import com.rainbowcockroach.table.tableandroidclient.transfer.WorkTransferScheduler
import com.rainbowcockroach.table.tableandroidclient.transfer.workName
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val FILE_SIZE = 1024 * 1024
private const val ALREADY_SENT = 300_000L
private const val WORK_TIMEOUT_MILLIS = 60_000L

private val DOWNLOADS = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

/**
 * DESIGN §7's WorkManager smoke test: a queued transfer runs, a retryable failure comes back
 * as a retry rather than a failure, and the attempt after it resumes the session the first
 * one opened instead of starting a new upload (conformance rules 2, 14).
 *
 * Needs the dev server the JVM suite uses, reachable from the device — `adb reverse
 * tcp:8080 tcp:8080` — with its address in the instrumentation arguments (the Gradle build
 * forwards `TABLE_URL`/`TABLE_API_KEY`).
 */
class TransferWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = (context as TableApp).container
    private val scheduler = WorkTransferScheduler(context)
    private val sources = mutableListOf<Uri>()

    private lateinit var client: TableClient
    private lateinit var workManager: WorkManager

    @Before
    fun startWithAnEmptyQueue() {
        val url = argument("TABLE_URL")
        val key = argument("TABLE_API_KEY")
        assumeTrue("no TABLE_URL/TABLE_API_KEY in the instrumentation arguments", url != null && key != null)
        client = TableClient(url!!, key!!, allowInsecureHttp = true)
        try {
            client.listFiles()
        } catch (io: IOException) {
            assumeTrue("table-server at $url is not reachable from the device: $io", false)
        }

        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        runBlocking {
            container.store.all().forEach { container.transfers.dismiss(it.id) }
            useServer(url)
        }
        sweepServer()
    }

    @After
    fun sweepServer() {
        if (::client.isInitialized) client.listFiles().forEach {
            // An unfinished session is not a file yet, and DELETE /files only knows about files.
            if (it.state == FileState.UPLOADING) client.abortUpload(it.id) else client.deleteFile(it.id)
        }
        sources.forEach { context.contentResolver.delete(it, null, null) }
        sources.clear()
    }

    @Test
    fun aQueuedUploadRunsToDoneThroughWorkManager() = runBlocking {
        val bytes = Random.nextBytes(FILE_SIZE)
        val source = mediaStoreSource("worker-upload.bin", bytes)

        container.transfers.upload(source.toString(), "worker-upload.bin", bytes.size.toLong())
        val record = assertNotNull(container.store.all().singleOrNull())
        startWork(record.id)

        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(record.id).state, whyItStopped(record.id))
        assertEquals(TransferState.DONE, container.store.get(record.id)!!.state)
        val onServer = client.listFiles().single()
        assertEquals(sha256Hex(bytes.inputStream()), onServer.sha256)
        assertEquals(bytes.size.toLong(), onServer.size)
    }

    @Test
    fun aRetryableFailureIsRetriedAndResumesTheSameSession() = runBlocking {
        val bytes = Random.nextBytes(FILE_SIZE)
        val source = mediaStoreSource("worker-resume.bin", bytes)
        val session = halfSentSession("worker-resume.bin", bytes)
        val record = TransferRecord(
            id = "resume-me",
            direction = TransferDirection.UPLOAD,
            name = "worker-resume.bin",
            size = bytes.size.toLong(),
            remoteId = session,
            sha256 = sha256Hex(bytes.inputStream()),
            sourceUri = source.toString(),
        )
        container.store.put(record)

        useServer("http://127.0.0.1:1")
        startWork(record.id)

        val retrying = awaitRetryOrFinish(record.id)
        assertEquals(WorkInfo.State.ENQUEUED, retrying.state, "a refused connection is not a failure")
        assertEquals(1, retrying.runAttemptCount)
        val afterFailure = container.store.get(record.id)!!
        assertTrue(afterFailure.failure!!.retryable)
        assertEquals(session, afterFailure.remoteId, "the session has to outlive the attempt")

        useServer(argument("TABLE_URL")!!)
        testDriver.setAllConstraintsMet(retrying.id)

        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(record.id).state, whyItStopped(record.id))
        val finished = container.store.get(record.id)!!
        assertEquals(TransferState.DONE, finished.state)
        assertEquals(session, finished.remoteId, "rule 2: resume the session, never start a new one")
        // Only bytes appended at the committed offset can add up to the declared hash.
        assertEquals(sha256Hex(bytes.inputStream()), client.listFiles().single { it.id == session }.sha256)
    }

    private fun whyItStopped(transferId: String) =
        runBlocking { container.store.get(transferId) }?.failure?.message.orEmpty()

    private val testDriver get() = WorkManagerTestInitHelper.getTestDriver(context)!!

    private fun startWork(transferId: String) {
        scheduler.schedule(transferId)
        testDriver.setAllConstraintsMet(workInfo(transferId).id)
    }

    private fun workInfo(transferId: String) =
        workManager.getWorkInfosForUniqueWork(workName(transferId)).get().single()

    // A CoroutineWorker runs off the WorkManager executor, so even a synchronous test
    // executor returns before the transfer has finished.
    private fun awaitFinished(transferId: String) = await(transferId) { it.state.isFinished }

    private fun awaitRetryOrFinish(transferId: String) = await(transferId) {
        it.state.isFinished || (it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0)
    }

    private fun await(transferId: String, settled: (WorkInfo) -> Boolean): WorkInfo {
        val deadline = System.currentTimeMillis() + WORK_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val info = workInfo(transferId)
            if (settled(info)) return info
            Thread.sleep(50)
        }
        error("the work for $transferId never settled")
    }

    /** The offset the resumed attempt has to pick up from. */
    private fun halfSentSession(name: String, bytes: ByteArray): String {
        val id = client.createUpload(name, bytes.size.toLong(), sha256Hex(bytes.inputStream()))
        val sent = bytes.copyOfRange(0, ALREADY_SENT.toInt()).inputStream()
        val result = client.appendBytes(id, 0, sent, ALREADY_SENT)
        assertTrue(result is AppendResult.Incomplete, "the session should still want the rest")
        return id
    }

    /** A real `content://` source, which is the only kind the queue knows how to re-open. */
    private fun mediaStoreSource(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.SIZE, bytes.size)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(DOWNLOADS, values)!!
        sources += uri
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        context.contentResolver.update(uri, published, null, null)
        return uri
    }

    private suspend fun useServer(url: String) {
        container.settings.setHost(url, allowInsecureHttp = true)
        container.settings.setApiKey(argument("TABLE_API_KEY")!!)
    }

    private fun argument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }
}
