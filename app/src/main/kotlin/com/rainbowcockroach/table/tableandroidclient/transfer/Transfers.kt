package com.rainbowcockroach.table.tableandroidclient.transfer

/** DESIGN §3: `queued → running → verifying → done | failed`. */
enum class TransferState { QUEUED, RUNNING, VERIFYING, DONE, FAILED }

/** Why a transfer stopped. [retryable] separates "try again" from "this will never work". */
data class TransferFailure(val message: String, val retryable: Boolean)

/** One queued transfer. The [target] is kept whole so a retry needs nothing else. */
data class Transfer(
    val target: DownloadTarget,
    val state: TransferState,
    val bytesDone: Long = 0L,
    val failure: TransferFailure? = null,
    val publishedName: String? = null,
) {
    val id: String get() = target.id
    val name: String get() = target.name
    val size: Long get() = target.size
}
