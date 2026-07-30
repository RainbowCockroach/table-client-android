package com.rainbowcockroach.table.tableandroidclient

import android.app.Application
import android.content.Context
import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.api.defaultHttpClient
import com.rainbowcockroach.table.tableandroidclient.settings.SettingsStore
import com.rainbowcockroach.table.tableandroidclient.settings.TableSettings
import com.rainbowcockroach.table.tableandroidclient.transfer.ContentUploadSources
import com.rainbowcockroach.table.tableandroidclient.transfer.DownloadTask
import com.rainbowcockroach.table.tableandroidclient.transfer.MediaStoreDownloadPublisher
import com.rainbowcockroach.table.tableandroidclient.transfer.RoomTransferStore
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferNotifications
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferQueue
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferRunner
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferStore
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferTasks
import com.rainbowcockroach.table.tableandroidclient.transfer.UploadIntake
import com.rainbowcockroach.table.tableandroidclient.transfer.UploadStaging
import com.rainbowcockroach.table.tableandroidclient.transfer.UploadTask
import com.rainbowcockroach.table.tableandroidclient.transfer.WorkTransferScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class TableApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Rule 14: whatever the last process left unfinished goes back to WorkManager here.
        container.resumeTransfers()
    }
}

/**
 * The single graph of long-lived objects, shared by the UI and by the transfer worker —
 * which is why it hangs off the Application rather than an Activity.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob())
    private val http = defaultHttpClient()
    val store: TransferStore = RoomTransferStore(appContext)

    val settings = SettingsStore(appContext)

    private val staging = UploadStaging(File(appContext.filesDir, "staged-uploads"))

    val transfers = TransferQueue(
        store = store,
        scheduler = WorkTransferScheduler(appContext),
        uploadOnWifiOnly = { settings.settings.first().uploadOnWifiOnly },
        staging = staging,
    )

    val uploads = UploadIntake(appContext.contentResolver, transfers, staging)

    val notifications = TransferNotifications(appContext)

    val runner = TransferRunner(
        store = store,
        clientFor = ::currentClientOrNull,
        attempt = TransferTasks(
            downloads = DownloadTask(
                tempDir = File(appContext.cacheDir, "downloads"),
                publisher = MediaStoreDownloadPublisher(appContext.contentResolver),
            ),
            uploads = UploadTask(ContentUploadSources(appContext.contentResolver)),
        ),
    )

    fun resumeTransfers() {
        scope.launch { transfers.resumeUnfinished() }
    }

    /** Shares one connection pool across every client; rule 13 is enforced by the constructor. */
    fun clientFor(settings: TableSettings): TableClient =
        TableClient(settings.hostUrl, settings.apiKey, settings.allowInsecureHttp, http)

    private suspend fun currentClientOrNull(): TableClient? =
        settings.settings.first().takeIf { it.isConfigured }?.let(::clientFor)
}
