package com.rainbowcockroach.table.tableandroidclient.transfer

/**
 * Whatever actually runs the queue. On Android that is WorkManager
 * ([WorkTransferScheduler]); tests substitute a recorder.
 */
interface TransferScheduler {
    /** Schedules [id] unless it is already scheduled — re-enqueueing must never duplicate work. */
    fun schedule(id: String)

    /** Runs [id] now, dropping any pending backoff: the user asked for it. */
    fun runNow(id: String)

    fun cancel(id: String)
}
