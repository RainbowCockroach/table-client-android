package com.rainbowcockroach.table.tableandroidclient.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract

private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"
private const val DOWNLOAD_DOCUMENT_ID = "primary:Download"
private const val DIRECTORY_MIME_TYPE = "vnd.android.document/directory"

/**
 * The Android half of `../UI.md` §5's reveal: the file manager showing `Download/`, which is
 * where every taken file is published. Null when the device answers neither intent.
 */
fun downloadsFolderIntent(context: Context): Intent? =
    listOf(viewDownloadsScreen(), browseDownloadFolder())
        .firstOrNull { it.resolveActivity(context.packageManager) != null }

private fun viewDownloadsScreen() = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)

/** What a ROM without the system Downloads screen still has, as long as it ships DocumentsUI. */
private fun browseDownloadFolder() = Intent(Intent.ACTION_VIEW).setDataAndType(
    DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER, DOWNLOAD_DOCUMENT_ID),
    DIRECTORY_MIME_TYPE,
)
