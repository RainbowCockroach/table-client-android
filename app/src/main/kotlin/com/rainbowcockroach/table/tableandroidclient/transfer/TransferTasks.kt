package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.TableClient

/** Sends a record to the task for its direction; the two halves share nothing else. */
class TransferTasks(
    private val downloads: DownloadTask,
    private val uploads: UploadTask,
) : TransferAttempt {

    override fun run(
        client: TableClient,
        record: TransferRecord,
        report: TransferReport,
    ): TransferResult = when (record.direction) {
        TransferDirection.DOWNLOAD -> downloads.run(client, record.downloadTarget(), report::bytes)
        TransferDirection.UPLOAD -> uploads.run(client, record, report::session, report::bytes)
    }
}
