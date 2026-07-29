package com.rainbowcockroach.table.tableandroidclient

import android.app.Application
import android.content.Context
import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.api.defaultHttpClient
import com.rainbowcockroach.table.tableandroidclient.settings.SettingsStore
import com.rainbowcockroach.table.tableandroidclient.settings.TableSettings
import com.rainbowcockroach.table.tableandroidclient.transfer.DownloadQueue
import com.rainbowcockroach.table.tableandroidclient.transfer.DownloadTask
import com.rainbowcockroach.table.tableandroidclient.transfer.MediaStoreDownloadPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class TableApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/**
 * The single graph of long-lived objects. It outlives the Activity so a download survives
 * rotation; surviving process death is WorkManager's job, from C4 on.
 */
class AppContainer(context: Context) {

    private val scope = CoroutineScope(SupervisorJob())
    private val http = defaultHttpClient()

    val settings = SettingsStore(context)

    private val downloadTask = DownloadTask(
        tempDir = File(context.cacheDir, "downloads"),
        publisher = MediaStoreDownloadPublisher(context.contentResolver),
    )

    val downloads = DownloadQueue(
        scope = scope,
        clientFor = ::currentClientOrNull,
        download = { client, target, onProgress ->
            withContext(Dispatchers.IO) { downloadTask.run(client, target, onProgress) }
        },
    )

    /** Shares one connection pool across every client; rule 13 is enforced by the constructor. */
    fun clientFor(settings: TableSettings): TableClient =
        TableClient(settings.hostUrl, settings.apiKey, settings.allowInsecureHttp, http)

    private suspend fun currentClientOrNull(): TableClient? =
        settings.settings.first().takeIf { it.isConfigured }?.let(::clientFor)
}
