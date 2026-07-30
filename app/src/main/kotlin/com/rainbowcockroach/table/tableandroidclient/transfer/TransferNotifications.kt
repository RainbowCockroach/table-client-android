package com.rainbowcockroach.table.tableandroidclient.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

private const val CHANNEL_ID = "transfers"

/**
 * The notification a long transfer runs its foreground service behind (DESIGN §3).
 *
 * Completion and failure notifications are C5; this one exists so the transfer keeps
 * running when the app is not in front of the user.
 */
class TransferNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun progress(notificationId: Int, record: TransferRecord): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(record.name)
            .setContentText(statusText(record))
            .setSmallIcon(iconFor(record.direction))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percentOf(record), record.state == TransferState.VERIFYING)
            .build()
        return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }
}

private fun statusText(record: TransferRecord): String = when (record.direction) {
    TransferDirection.UPLOAD -> if (record.state == TransferState.VERIFYING) "Finishing upload" else "Uploading"
    TransferDirection.DOWNLOAD -> if (record.state == TransferState.VERIFYING) "Verifying" else "Downloading"
}

private fun iconFor(direction: TransferDirection) = when (direction) {
    TransferDirection.UPLOAD -> android.R.drawable.stat_sys_upload
    TransferDirection.DOWNLOAD -> android.R.drawable.stat_sys_download
}

private fun percentOf(record: TransferRecord): Int =
    if (record.size <= 0) 0 else ((record.bytesDone * 100) / record.size).toInt().coerceIn(0, 100)
