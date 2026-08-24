package com.rainbowcockroach.table.tableandroidclient.transfer

import android.content.ContentResolver
import com.rainbowcockroach.table.tableandroidclient.testsupport.InMemoryTransferStore
import com.rainbowcockroach.table.tableandroidclient.testsupport.RecordingScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rung 3 of rule 16's ladder, from text to a queued row. */
class TextIntakeTest {

    @get:Rule
    val scratch = TemporaryFolder()

    private val store = InMemoryTransferStore()
    private val staged by lazy { scratch.newFolder("staged") }
    private val staging by lazy { UploadStaging(staged) }
    private val intake by lazy {
        UploadIntake(
            // The text rung reads no content provider; the resolver is rung 1's.
            resolver = object : ContentResolver(null) {},
            queue = TransferQueue(store = store, scheduler = RecordingScheduler(), staging = staging),
            staging = staging,
        )
    }

    @Test
    fun `a shared line becomes one upload of its own bytes`() = runTest {
        val result = intake.accept("Milk and bread\nand jam")

        assertEquals(listOf("Milk and bread.txt"), result.queued)
        assertEquals(0, result.rejected)
        val record = store.all().single()
        assertEquals(TransferDirection.UPLOAD, record.direction)
        assertEquals("Milk and bread.txt", record.name)
        val copy = File(java.net.URI(record.sourceUri!!))
        assertEquals("Milk and bread\nand jam", copy.readText(Charsets.UTF_8))
        assertEquals(copy.length(), record.size)
    }

    @Test
    fun `the sender's own name wins over the first line`() = runTest {
        intake.accept("https://example.com/a", offered = "Example Domain")

        assertEquals("Example Domain.txt", store.all().single().name)
    }

    /** Rejected before it becomes a row: an empty file on the table helps no one. */
    @Test
    fun `whitespace is refused rather than queued`() = runTest {
        val result = intake.accept("  \n\t\n ")

        assertEquals(emptyList(), result.queued)
        assertEquals(1, result.rejected)
        assertTrue(store.all().isEmpty())
        assertEquals(emptyList(), staged.listFiles()?.toList().orEmpty())
    }
}
