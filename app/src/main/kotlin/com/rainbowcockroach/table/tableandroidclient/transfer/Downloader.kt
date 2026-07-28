package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.AckResult
import com.rainbowcockroach.table.tableandroidclient.api.DownloadStream
import com.rainbowcockroach.table.tableandroidclient.api.FileGoneException
import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.api.TableException
import com.rainbowcockroach.table.tableandroidclient.api.TableFile
import com.rainbowcockroach.table.tableandroidclient.crypto.Sha256Hasher
import com.rainbowcockroach.table.tableandroidclient.crypto.TRANSFER_BUFFER_BYTES
import java.io.File
import java.io.RandomAccessFile

/** The server-declared identity of a file to download; survives process death in the queue. */
data class DownloadTarget(
    val id: String,
    val name: String,
    val size: Long,
    val sha256: String,
) {
    constructor(file: TableFile) : this(file.id, file.name, file.size, file.sha256)
}

sealed interface DownloadOutcome {
    /**
     * Complete, verified, fsynced and acked. The temp file must survive until it has been
     * published to the user-visible location (conformance rule 11).
     */
    data class Verified(val tempFile: File, val sha256: String, val ack: AckResult) : DownloadOutcome

    /** The stream ended early. The next attempt resumes from [bytesOnDisk] (rule 5). */
    data class Incomplete(val bytesOnDisk: Long) : DownloadOutcome

    /** The local copy failed verification and was discarded; re-download from zero (rules 6, 10). */
    data class Corrupt(val reason: String) : DownloadOutcome

    /** The server no longer has the file and no complete local copy exists. */
    data object Gone : DownloadOutcome
}

/**
 * One attempt at fetching a file into a temp file, verifying it, and acking it.
 *
 * Retry and backoff belong to the caller: every attempt resumes from whatever the temp
 * file already holds rather than restarting (conformance rules 5, 14).
 */
class Downloader(private val client: TableClient) {

    fun download(
        target: DownloadTarget,
        tempFile: File,
        onProgress: (bytesOnDisk: Long) -> Unit = {},
    ): DownloadOutcome {
        val localHash = if (tempFile.length() >= target.size) {
            // A previous attempt finished the bytes but not the ack; nothing left to fetch.
            tempFile.inputStream().use { Sha256Hasher().apply { updateFrom(it) }.hex() }
        } else {
            val fetched = try {
                fetch(target, tempFile, onProgress)
            } catch (gone: FileGoneException) {
                return DownloadOutcome.Gone
            }
            if (fetched.bytesOnDisk < target.size) return DownloadOutcome.Incomplete(fetched.bytesOnDisk)
            fetched.sha256
        }
        return verifyAndAck(target, tempFile, localHash)
    }

    private class Fetched(val bytesOnDisk: Long, val sha256: String)

    /** Streams the missing tail into [tempFile], hashing the complete file as it goes. */
    private fun fetch(target: DownloadTarget, tempFile: File, onProgress: (Long) -> Unit): Fetched {
        client.openDownload(target.id, rangeFrom = tempFile.length()).use { stream ->
            checkDeclaration(target, stream)
            val hasher = Sha256Hasher()
            RandomAccessFile(tempFile, "rw").use { sink ->
                // A server that ignored the Range answers from byte 0, so whatever we had
                // is superseded rather than appended to.
                sink.setLength(stream.rangeStart)
                rehashKeptBytes(tempFile, stream.rangeStart, hasher)
                sink.seek(stream.rangeStart)
                val onDisk = copy(stream, sink, hasher, stream.rangeStart, onProgress)
                // Rule 7: durable before anything acks it.
                sink.fd.sync()
                return Fetched(onDisk, hasher.hex())
            }
        }
    }

    /** DESIGN §2: a resumed download rebuilds its digest from the bytes already on disk. */
    private fun rehashKeptBytes(tempFile: File, keptBytes: Long, hasher: Sha256Hasher) {
        if (keptBytes == 0L) return
        tempFile.inputStream().use { hasher.updateFrom(it) }
        if (hasher.bytesHashed != keptBytes) {
            throw TableException("partial file shrank to ${hasher.bytesHashed} while rebuilding the digest")
        }
    }

    private fun copy(
        stream: DownloadStream,
        sink: RandomAccessFile,
        hasher: Sha256Hasher,
        startOffset: Long,
        onProgress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        val source = stream.source
        var onDisk = startOffset
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            sink.write(buffer, 0, read)
            hasher.update(buffer, read)
            onDisk += read
            onProgress(onDisk)
        }
        return onDisk
    }

    private fun verifyAndAck(target: DownloadTarget, tempFile: File, localHash: String): DownloadOutcome {
        val localSize = tempFile.length()
        if (localSize != target.size) {
            return discard(tempFile, "expected ${target.size} bytes, local copy has $localSize")
        }
        if (localHash != target.sha256) {
            return discard(tempFile, "SHA-256 mismatch: expected ${target.sha256}, computed $localHash")
        }
        return when (val ack = client.ack(target.id, localHash)) {
            // Rule 9: another device got there first, and the file being gone is the point.
            AckResult.DELETED, AckResult.ALREADY_GONE ->
                DownloadOutcome.Verified(tempFile, localHash, ack)

            // Rule 10: the server disagrees about the bytes, so the local copy loses.
            AckResult.HASH_MISMATCH ->
                discard(tempFile, "server rejected the acked hash $localHash")
        }
    }

    private fun discard(tempFile: File, reason: String): DownloadOutcome.Corrupt {
        tempFile.delete()
        return DownloadOutcome.Corrupt(reason)
    }

    /** Rule 6 verifies against what the server declares on the wire, so it has to declare it. */
    private fun checkDeclaration(target: DownloadTarget, stream: DownloadStream) {
        val declaredSize = stream.totalSize
            ?: throw TableException("download ${target.id}: server declared no length")
        val declaredHash = stream.checksumSha256
            ?: throw TableException("download ${target.id}: no X-Checksum-SHA256 header")
        if (declaredSize != target.size || declaredHash != target.sha256) {
            throw TableException(
                "download ${target.id}: server now declares $declaredSize/$declaredHash, " +
                    "queued as ${target.size}/${target.sha256}"
            )
        }
    }
}
