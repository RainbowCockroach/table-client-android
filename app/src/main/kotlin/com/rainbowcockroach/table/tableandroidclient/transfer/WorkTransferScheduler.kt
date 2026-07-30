package com.rainbowcockroach.table.tableandroidclient.transfer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

internal const val TRANSFER_ID_KEY = "transfer_id"

/** Long enough that a flapping network settles, short enough to feel automatic. */
private const val BACKOFF_SECONDS = 10L

/**
 * DESIGN §3: one WorkRequest per file, which is what makes the queue survive process death
 * and reboot. The unique work name is the record id, so a record can never have two workers.
 */
class WorkTransferScheduler(private val context: Context) : TransferScheduler {

    // Resolved per call: WorkManager may not be initialized when the container is built.
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun schedule(id: String, unmetered: Boolean) =
        enqueue(id, ExistingWorkPolicy.KEEP, unmetered)

    override fun runNow(id: String, unmetered: Boolean) =
        enqueue(id, ExistingWorkPolicy.REPLACE, unmetered)

    override fun cancel(id: String) {
        workManager.cancelUniqueWork(workName(id))
    }

    private fun enqueue(id: String, policy: ExistingWorkPolicy, unmetered: Boolean) {
        val network = if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf(TRANSFER_ID_KEY to id))
            .setConstraints(Constraints(requiredNetworkType = network))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TRANSFER_WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(workName(id), policy, request)
    }
}

const val TRANSFER_WORK_TAG = "table-transfer"

fun workName(transferId: String) = "transfer-$transferId"
