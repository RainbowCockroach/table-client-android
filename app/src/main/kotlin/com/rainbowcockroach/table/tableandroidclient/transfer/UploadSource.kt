package com.rainbowcockroach.table.tableandroidclient.transfer

import java.io.EOFException
import java.io.File
import java.io.InputStream

/**
 * A file to upload, readable more than once.
 *
 * Content-provider streams are not seekable, so resuming re-opens the source and skips
 * to the committed offset (DESIGN §2) — which makes re-openability the one requirement.
 */
interface UploadSource {
    val name: String
    val size: Long

    /** Opens a fresh stream positioned at byte 0. */
    fun openStream(): InputStream
}

class FileUploadSource(
    private val file: File,
    override val name: String = file.name,
) : UploadSource {
    override val size: Long get() = file.length()
    override fun openStream(): InputStream = file.inputStream()
}

/**
 * Skips exactly [count] bytes or throws.
 *
 * `InputStream.skip` may return short — or zero forever on some content providers —
 * so a bare `skip(offset)` can silently upload from the wrong offset.
 */
internal fun InputStream.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            if (read() < 0) throw EOFException("source ended $remaining bytes before offset $count")
            remaining--
        }
    }
}
