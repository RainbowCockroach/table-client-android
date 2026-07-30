package com.rainbowcockroach.table.tableandroidclient.transfer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rainbowcockroach.table.tableandroidclient.TableApp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One file's transfer, as WorkManager runs it: constraints, backoff and process-death
 * survival are the framework's, the verdict is [TransferRunner]'s.
 */
class TransferWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    private val container get() = (applicationContext as TableApp).container

    override suspend fun doWork(): Result = coroutineScope {
        val transferId = inputData.getString(TRANSFER_ID_KEY) ?: return@coroutineScope Result.failure()
        val progress = launch { showProgressWhileRunning(transferId) }
        val outcome = try {
            container.runner.run(transferId, runAttemptCount)
        } finally {
            progress.cancel()
        }
        if (outcome != RunOutcome.RETRY) {
            container.store.get(transferId)?.let(container.notifications::settled)
        }
        when (outcome) {
            RunOutcome.DONE -> Result.success()
            RunOutcome.RETRY -> Result.retry()
            RunOutcome.GAVE_UP -> Result.failure()
        }
    }

    private suspend fun showProgressWhileRunning(transferId: String) {
        var foregroundAllowed = true
        container.transfers.transfers
            .mapNotNull { records -> records.firstOrNull { it.id == transferId } }
            .distinctUntilChanged()
            // Stop at the settled record: WorkManager takes the notification down once the worker
            // returns, and a post issued that same instant arrives after the takedown has run.
            .takeWhile { foregroundAllowed && !it.isFinished }
            .collect { record ->
                // Android 12+ refuses a foreground service started from the background, which is
                // exactly where a queue resumed after process death starts. Nothing about showing
                // progress is worth failing a transfer for, so the transfer runs on as ordinary
                // background work and only the notification is lost.
                //
                // Uninterruptible because setForeground reaches the notification through
                // startService: abandoning it half-way lands the notification after the takedown
                // instead of before it, and an ongoing notification nobody owns never goes away.
                foregroundAllowed = withContext(NonCancellable) {
                    runCatching { setForeground(container.notifications.progress(record)) }.isSuccess
                }
            }
    }
}
