package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.api.isRetryable

/** The source is gone for good: the share grant was revoked, or the file was deleted. */
class SourceUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Re-opens a queued upload's persisted source. */
fun interface UploadSources {
    /** Throws [SourceUnavailableException] when the URI can no longer be read. */
    fun open(record: TransferRecord): UploadSource
}

/**
 * The upload half of the conformance checklist for one file: hash and declare, then push
 * the session to completion.
 *
 * One attempt. A retry re-enters here with the session id the previous attempt persisted
 * and resumes from the server's committed offset (rule 2); rules 3 and the session-gone
 * case drop that id so the next attempt declares the file afresh.
 */
class UploadTask(private val sources: UploadSources) {

    fun run(
        client: TableClient,
        record: TransferRecord,
        onSession: (UploadSession?) -> Unit,
        onProgress: (bytesSent: Long) -> Unit = {},
    ): TransferResult {
        val uploader = Uploader(client)
        return try {
            val source = sources.open(record)
            val session = record.openSession()
                ?: uploader.createSession(source).also(onSession)
            when (val outcome = uploader.upload(session, source, onProgress)) {
                is UploadOutcome.Finalized -> TransferResult.Done()

                is UploadOutcome.Interrupted ->
                    retryable("stopped at ${outcome.committedOffset} of ${session.size} bytes")

                UploadOutcome.SessionGone -> startOver(onSession, "the upload session expired")

                // Rule 3: the server has already discarded the session, so only a new one can work.
                is UploadOutcome.Rejected ->
                    startOver(onSession, "the server rejected the upload (${outcome.message})")
            }
        } catch (unavailable: SourceUnavailableException) {
            // DESIGN §6: a revoked grant never comes back on its own.
            permanent("${unavailable.message} — share or pick ${record.name} again")
        } catch (failure: Exception) {
            TransferResult.Failed(
                TransferFailure(failure.message ?: failure.toString(), failure.isRetryable)
            )
        }
    }

    private fun startOver(onSession: (UploadSession?) -> Unit, reason: String): TransferResult {
        onSession(null)
        return retryable("$reason — sending it again from the start")
    }
}

private fun TransferRecord.openSession(): UploadSession? {
    val sessionId = remoteId ?: return null
    val declaredHash = sha256 ?: return null
    return UploadSession(sessionId, name, size, declaredHash)
}
