package com.rainbowcockroach.table.tableandroidclient.ui

import com.rainbowcockroach.table.tableandroidclient.api.FileState
import com.rainbowcockroach.table.tableandroidclient.api.TableFile
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferDirection
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferRecord
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferState
import java.time.Instant

/** `../UI.md` §4's second line: `size · time` on the table. */
internal fun describe(file: TableFile, now: Instant): String = when (file.state) {
    // Rule 15: no TTL until the upload finalizes, so there is nothing to count down yet.
    FileState.UPLOADING -> "${formatBytes(file.bytesReceived)} of ${formatBytes(file.size)} · uploading"
    FileState.AVAILABLE -> listOfNotNull(
        formatBytes(file.size),
        formatExpiry(file.expiresAt, now),
    ).joinToString(" · ")
}

/** §4's second line in the queue: what this transfer is doing. */
internal fun label(transfer: TransferRecord, gone: Boolean = false): String = when (transfer.state) {
    TransferState.QUEUED -> "Queued"
    TransferState.RUNNING -> "${formatBytes(transfer.bytesDone)} of ${formatBytes(transfer.size)}"
    TransferState.VERIFYING ->
        if (transfer.direction == TransferDirection.UPLOAD) "Finishing" else "Verifying"

    TransferState.DONE -> when {
        transfer.publishedName == null -> "Sent"
        gone -> "moved or deleted"
        else -> "Saved to Downloads as ${transfer.publishedName}"
    }
    // WorkManager owns the retry; the button is only for someone who would rather not wait.
    TransferState.FAILED -> if (transfer.failure?.retryable == true) "Retrying soon" else "Failed"
}
