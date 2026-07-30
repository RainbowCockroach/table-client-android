package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.crypto.TRANSFER_BUFFER_BYTES
import java.io.File
import java.io.InputStream

/** The staged copies a queue may have to clean up; [None] for a queue that never makes any. */
interface StagedUploads {

    fun discard(record: TransferRecord)

    /** Drops every copy no record in [live] still needs. */
    fun sweep(live: List<TransferRecord>)

    object None : StagedUploads {
        override fun discard(record: TransferRecord) = Unit
        override fun sweep(live: List<TransferRecord>) = Unit
    }
}

/** Private copies of upload sources whose `content://` grant will not last (see [UploadIntake]). */
class UploadStaging(private val directory: File) : StagedUploads {

    /** Throws if the copy cannot be completed; nothing is left behind when it does. */
    fun stage(open: () -> InputStream): File {
        directory.mkdirs()
        val copy = File.createTempFile("staged-", ".upload", directory)
        try {
            open().use { source ->
                copy.outputStream().use { source.copyTo(it, TRANSFER_BUFFER_BYTES) }
            }
        } catch (failure: Throwable) {
            copy.delete()
            throw failure
        }
        return copy
    }

    override fun discard(record: TransferRecord) {
        val uri = record.sourceUri ?: return
        staged().firstOrNull { it.toURI().toString() == uri }?.delete()
    }

    override fun sweep(live: List<TransferRecord>) {
        val needed = live.mapNotNullTo(mutableSetOf()) { it.sourceUri }
        staged().filterNot { it.toURI().toString() in needed }.forEach { it.delete() }
    }

    private fun staged(): List<File> = directory.listFiles()?.toList().orEmpty()
}

/** How a staged copy is named in a record, and what [ContentUploadSources] opens it by. */
fun stagedSourceUri(copy: File): String = copy.toURI().toString()
