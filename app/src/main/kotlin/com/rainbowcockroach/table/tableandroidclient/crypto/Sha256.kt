package com.rainbowcockroach.table.tableandroidclient.crypto

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Buffer size for every streaming read in the client; DESIGN §2 calls for ~1 MiB. */
const val TRANSFER_BUFFER_BYTES = 1 shl 20

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Lowercase hex, the only SHA-256 encoding the wire protocol uses. */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xff
        out[i * 2] = HEX_DIGITS[b ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[b and 0x0f]
    }
    return String(out)
}

/** True for the lowercase-hex SHA-256 shape the API contract requires. */
fun String.isSha256Hex(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

/**
 * Incremental SHA-256 over a byte stream.
 *
 * A download that resumes rebuilds its digest by re-feeding the partial temp file
 * ([updateFrom]) and then continues incrementally, so a resumed transfer still
 * hashes the complete file (conformance rule 6).
 */
class Sha256Hasher {
    private val digest = MessageDigest.getInstance("SHA-256")

    var bytesHashed: Long = 0L
        private set

    fun update(buffer: ByteArray, length: Int) {
        digest.update(buffer, 0, length)
        bytesHashed += length
    }

    /** Feeds the whole stream; returns the number of bytes read. Does not close it. */
    fun updateFrom(source: InputStream): Long {
        val before = bytesHashed
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            update(buffer, read)
        }
        return bytesHashed - before
    }

    fun hex(): String = digest.digest().toHex()
}

/** SHA-256 of the whole stream, in lowercase hex. Does not close it. */
fun sha256Hex(source: InputStream): String = Sha256Hasher().apply { updateFrom(source) }.hex()

/** SHA-256 of a file, in lowercase hex. */
fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }
