package com.rainbowcockroach.table.tableandroidclient.transfer

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The copies DESIGN §4 makes when a share grant cannot be persisted. */
class UploadStagingTest {

    @get:Rule
    val scratch = TemporaryFolder()

    private val directory by lazy { scratch.newFolder("staged") }
    private val staging by lazy { UploadStaging(directory) }

    @Test
    fun `a staged copy holds the whole source and is named in the record`() {
        val bytes = ByteArray(300_000) { it.toByte() }

        val copy = staging.stage { ByteArrayInputStream(bytes) }

        assertContentEquals(bytes, copy.readBytes())
        assertEquals(copy.toURI().toString(), stagedSourceUri(copy))
        assertEquals(directory, copy.parentFile)
    }

    /** A half-copied file would upload as a truncated one; nothing is better than that. */
    @Test
    fun `a source that dies mid-copy leaves no copy behind`() {
        assertFailsWith<IOException> { staging.stage { DiesAfter(1024) } }

        assertEquals(emptyList(), directory.listFiles()!!.toList())
    }

    @Test
    fun `discarding drops the record's own copy and nothing else`() {
        val mine = staging.stage { ByteArrayInputStream(ByteArray(8)) }
        val theirs = staging.stage { ByteArrayInputStream(ByteArray(8)) }

        staging.discard(upload(stagedSourceUri(mine)))

        assertFalse(mine.exists())
        assertTrue(theirs.exists())
    }

    @Test
    fun `a content uri record owns no copy to discard`() {
        val copy = staging.stage { ByteArrayInputStream(ByteArray(8)) }

        staging.discard(upload("content://docs/7"))

        assertTrue(copy.exists())
    }

    /** Rule 14's queue is the only thing that can still want a copy after a restart. */
    @Test
    fun `sweeping keeps what the live queue still needs`() {
        val wanted = staging.stage { ByteArrayInputStream(ByteArray(8)) }
        val orphan = staging.stage { ByteArrayInputStream(ByteArray(8)) }

        staging.sweep(listOf(upload(stagedSourceUri(wanted))))

        assertTrue(wanted.exists())
        assertFalse(orphan.exists())
    }

    private fun upload(sourceUri: String) = TransferRecord(
        id = "local-1",
        direction = TransferDirection.UPLOAD,
        name = "shared.bin",
        size = 8,
        sourceUri = sourceUri,
    )
}

private class DiesAfter(private val readable: Int) : InputStream() {

    private var read = 0

    override fun read(): Int {
        if (read++ >= readable) throw IOException("the provider gave up")
        return 0
    }
}
