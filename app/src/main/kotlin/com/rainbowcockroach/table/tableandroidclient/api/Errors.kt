package com.rainbowcockroach.table.tableandroidclient.api

import java.io.IOException

/** Base for every failure the client raises; all transfer paths deal in [IOException]. */
open class TableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A response outside the contract for that operation. */
open class TableHttpException(
    val statusCode: Int,
    val serverMessage: String?,
    operation: String,
) : TableException(
    "$operation failed: HTTP $statusCode" + (serverMessage?.let { " ($it)" } ?: "")
)

/** `401`: the API key is missing or wrong. Never worth retrying without user action. */
class TableAuthException(operation: String) : TableHttpException(401, null, operation)

/** `404`/`410` on download: the file is gone (acked elsewhere, expired, or upload aborted). */
class FileGoneException(val fileId: String, statusCode: Int) :
    TableHttpException(statusCode, null, "download $fileId")

/**
 * `416`: the requested range starts at or beyond the bytes the server holds.
 *
 * Expected against a live-relay upload that has not reached the resume point yet
 * (root DESIGN §2); the caller retries later.
 */
class RangeNotSatisfiableException(val fileId: String) :
    TableHttpException(416, null, "download $fileId")

/**
 * Whether retrying the same request unchanged could plausibly succeed.
 *
 * Transport failures and server-side faults are retryable; a rejected key or a
 * contract violation is not.
 */
val Throwable.isRetryable: Boolean
    get() = when (this) {
        is TableAuthException -> false
        is RangeNotSatisfiableException -> true
        is TableHttpException -> statusCode >= 500 || statusCode == 408 || statusCode == 429
        is IOException -> true
        else -> false
    }
