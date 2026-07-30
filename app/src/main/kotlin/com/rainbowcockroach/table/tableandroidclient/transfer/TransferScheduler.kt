package com.rainbowcockroach.table.tableandroidclient.transfer

/**
 * Whatever actually runs the queue. On Android that is WorkManager
 * ([WorkTransferScheduler]); tests substitute a recorder.
 *
 * [unmetered] carries DESIGN §6's Wi-Fi-only setting: the work waits for an unmetered
 * network rather than any connected one.
 */
interface TransferScheduler {
    /** Schedules [id] unless it is already scheduled — re-enqueueing must never duplicate work. */
    fun schedule(id: String, unmetered: Boolean)

    /** Runs [id] now, dropping any pending backoff: the user asked for it. */
    fun runNow(id: String, unmetered: Boolean)

    fun cancel(id: String)
}
