package com.rainbowcockroach.table.tableandroidclient.transfer

/** DESIGN §3: `queued → running → verifying → done | failed`. */
enum class TransferState { QUEUED, RUNNING, VERIFYING, DONE, FAILED }

enum class TransferDirection { UPLOAD, DOWNLOAD }

/** Why a transfer stopped. [retryable] separates "try again" from "this will never work". */
data class TransferFailure(val message: String, val retryable: Boolean)

/**
 * One row of the persistent queue.
 *
 * Conformance rule 14: this is everything a worker needs to resume after process death —
 * the upload session id, the file id, and the source URI — so the next attempt asks the
 * server where it got to instead of starting over.
 */
data class TransferRecord(
    val id: String,
    val direction: TransferDirection,
    val name: String,
    val size: Long,
    val state: TransferState = TransferState.QUEUED,
    /** Download: the file id, known from the listing. Upload: the session id, null until created. */
    val remoteId: String? = null,
    /** The declared hash: the server's for a download, ours for an upload (rule 1). */
    val sha256: String? = null,
    /** Upload: the `content://` source, re-opened and skipped to resume (DESIGN §2). */
    val sourceUri: String? = null,
    val bytesDone: Long = 0L,
    val failure: TransferFailure? = null,
    val publishedName: String? = null,
    val createdAt: Long = 0L,
) {
    val isFinished: Boolean get() = state == TransferState.DONE || state == TransferState.FAILED

    fun downloadTarget(): DownloadTarget = DownloadTarget(
        id = checkNotNull(remoteId) { "download $id has no file id" },
        name = name,
        size = size,
        sha256 = checkNotNull(sha256) { "download $id has no declared hash" },
    )
}

sealed interface TransferResult {
    /** Uploads finalize on the server; downloads carry the name they were published under. */
    data class Done(val publishedName: String? = null) : TransferResult

    data class Failed(val failure: TransferFailure) : TransferResult
}

internal fun retryable(message: String) =
    TransferResult.Failed(TransferFailure(message, retryable = true))

internal fun permanent(message: String) =
    TransferResult.Failed(TransferFailure(message, retryable = false))
