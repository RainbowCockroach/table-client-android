package com.rainbowcockroach.table.tableandroidclient.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.rainbowcockroach.table.tableandroidclient.transfer.mimeTypeOf

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

/**
 * `../UI.md` §4: the open target behind a landed row's body, and the landed *upload* row's
 * first slot — an upload's source is a `content://` grant in another app's store, which no
 * file manager can be pointed at. Null when nothing on the device can open it.
 */
fun openFileIntent(context: Context, uri: String, displayName: String): Intent? {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), mimeTypeOf(displayName))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return view.takeIf { it.resolveActivity(context.packageManager) != null }
}
