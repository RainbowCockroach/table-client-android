package com.rainbowcockroach.table.tableandroidclient.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.rainbowcockroach.table.tableandroidclient.MainActivity

private const val PROGRESS_CHANNEL_ID = "transfers"
private const val SETTLED_CHANNEL_ID = "transfers-settled"

private const val IMMUTABLE_UPDATE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

/**
 * The notifications of DESIGN §4: the one a long transfer runs its foreground service
 * behind, and the one that says how it ended.
 */
class TransferNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun progress(notificationId: Int, record: TransferRecord): ForegroundInfo {
        ensureChannel(PROGRESS_CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW)
        val notification = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setContentTitle(record.name)
            .setContentText(statusText(record))
            .setSmallIcon(iconFor(record.direction))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setProgress(100, percentOf(record), record.state == TransferState.VERIFYING)
            .build()
        return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Only for a settled transfer: a retryable failure is about to run again by itself. */
    fun settled(record: TransferRecord) {
        if (!record.isFinished) return
        ensureChannel(SETTLED_CHANNEL_ID, "Finished transfers", NotificationManager.IMPORTANCE_DEFAULT)
        val notification = NotificationCompat.Builder(context, SETTLED_CHANNEL_ID)
            .setContentTitle(record.name)
            .setContentText(outcomeText(record))
            .setSmallIcon(iconFor(record.direction))
            .setAutoCancel(true)
            .setContentIntent(openResult(record))
            .build()
        manager.notify(settledNotificationId(record.id), notification)
    }

    fun cancelSettled(transferId: String) = manager.cancel(settledNotificationId(transferId))

    /** DESIGN §4: tapping a finished download opens it; anything else opens the queue. */
    private fun openResult(record: TransferRecord): PendingIntent {
        val uri = record.publishedUri?.let(Uri::parse) ?: return openApp()
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeTypeOf(record.publishedName ?: record.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (view.resolveActivity(context.packageManager) == null) return openApp()
        return PendingIntent.getActivity(context, uri.hashCode(), view, IMMUTABLE_UPDATE)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        IMMUTABLE_UPDATE,
    )

    private fun ensureChannel(id: String, name: String, importance: Int) {
        manager.createNotificationChannel(NotificationChannel(id, name, importance))
    }
}

/** Distinct from the progress notification, which WorkManager cancels when the work ends. */
private fun settledNotificationId(transferId: String) = "settled:$transferId".hashCode()

private fun statusText(record: TransferRecord): String = when (record.direction) {
    TransferDirection.UPLOAD -> if (record.state == TransferState.VERIFYING) "Finishing upload" else "Uploading"
    TransferDirection.DOWNLOAD -> if (record.state == TransferState.VERIFYING) "Verifying" else "Downloading"
}

private fun outcomeText(record: TransferRecord): String = when {
    record.state == TransferState.FAILED -> record.failure?.message ?: "Failed"
    record.direction == TransferDirection.UPLOAD -> "Sent"
    else -> record.publishedName?.let { "Saved to Downloads as $it" } ?: "Downloaded"
}

private fun iconFor(direction: TransferDirection) = when (direction) {
    TransferDirection.UPLOAD -> android.R.drawable.stat_sys_upload
    TransferDirection.DOWNLOAD -> android.R.drawable.stat_sys_download
}

private fun percentOf(record: TransferRecord): Int =
    if (record.size <= 0) 0 else ((record.bytesDone * 100) / record.size).toInt().coerceIn(0, 100)
