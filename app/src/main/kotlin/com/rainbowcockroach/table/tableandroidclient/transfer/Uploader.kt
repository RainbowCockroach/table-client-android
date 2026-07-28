package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.AppendResult
import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.api.TableFile
import com.rainbowcockroach.table.tableandroidclient.crypto.sha256Hex

/** An open upload session. The queue persists it so a resumed worker keeps the same session. */
data class UploadSession(
    val id: String,
    val name: String,
    val size: Long,
    val sha256: String,
)

sealed interface UploadOutcome {
    /** The last byte landed and the server verified size and hash; the TTL is now running. */
    data class Finalized(val file: TableFile) : UploadOutcome

    /** The server holds [committedOffset] bytes and wants more; the next attempt continues there. */
    data class Interrupted(val committedOffset: Long) : UploadOutcome

    /** The session is gone (aborted or idle-GC'd). A new session is the only way forward. */
    data object SessionGone : UploadOutcome

    /**
     * Finalize rejected the bytes. Conformance rule 3: the server already discarded the
     * session, so the retry needs a brand-new one.
     */
    data class Rejected(val message: String?) : UploadOutcome
}

/**
 * One attempt at pushing an upload session to completion.
 *
 * Retry and backoff belong to the caller; each attempt asks the server where it got to
 * rather than assuming (conformance rules 2, 14).
 */
class Uploader(private val client: TableClient) {

    /** Conformance rule 1: size and SHA-256 are computed before the session is created. */
    fun createSession(source: UploadSource): UploadSession {
        val size = source.size
        val sha256 = source.openStream().use { sha256Hex(it) }
        val id = client.createUpload(source.name, size, sha256)
        return UploadSession(id, source.name, size, sha256)
    }

    fun upload(
        session: UploadSession,
        source: UploadSource,
        onProgress: (bytesSent: Long) -> Unit = {},
    ): UploadOutcome {
        // Rule 2: the committed offset comes from the server, never from local bookkeeping.
        val offset = client.uploadOffset(session.id) ?: return UploadOutcome.SessionGone
        if (offset >= session.size) {
            throw IllegalStateException(
                "session ${session.id} reports offset $offset for a ${session.size}-byte file"
            )
        }

        val result = source.openStream().use { stream ->
            stream.skipFully(offset)
            client.appendBytes(session.id, offset, stream, session.size - offset, onProgress)
        }

        return when (result) {
            is AppendResult.Finalized -> UploadOutcome.Finalized(result.file)
            is AppendResult.Incomplete -> UploadOutcome.Interrupted(result.committedOffset)
            is AppendResult.OffsetMismatch -> UploadOutcome.Interrupted(result.committedOffset)
            is AppendResult.SessionGone -> UploadOutcome.SessionGone
            is AppendResult.FinalizeRejected -> UploadOutcome.Rejected(result.message)
        }
    }
}
