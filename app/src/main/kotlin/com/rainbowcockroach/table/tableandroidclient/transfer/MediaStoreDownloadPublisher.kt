package com.rainbowcockroach.table.tableandroidclient.transfer

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.rainbowcockroach.table.tableandroidclient.crypto.TRANSFER_BUFFER_BYTES
import java.io.File
import java.io.IOException

private const val DEFAULT_MIME_TYPE = "application/octet-stream"

private val DOWNLOADS = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

/** Publishes into `Download/` via MediaStore, which needs no storage permission on API 29+. */
class MediaStoreDownloadPublisher(private val resolver: ContentResolver) : DownloadPublisher {

    override fun publish(source: File, displayName: String): PublishedDownload {
        val uri = resolver.insert(DOWNLOADS, pendingEntry(source, displayName))
            ?: throw IOException("MediaStore refused an entry for $displayName")
        try {
            resolver.openOutputStream(uri).use { sink ->
                sink ?: throw IOException("MediaStore gave no output stream for $displayName")
                source.inputStream().use { it.copyTo(sink, TRANSFER_BUFFER_BYTES) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (failure: Throwable) {
            // A pending entry is invisible to the user but still occupies its name.
            resolver.delete(uri, null, null)
            throw failure
        }
        return PublishedDownload(publishedName(uri) ?: displayName, uri.toString())
    }

    private fun pendingEntry(source: File, displayName: String) = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, freeName(displayName))
        put(MediaStore.Downloads.MIME_TYPE, mimeTypeOf(displayName))
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.SIZE, source.length())
        put(MediaStore.Downloads.IS_PENDING, 1)
    }

    /**
     * Scoped storage hides other apps' files, so this only sees collisions with our own
     * downloads; MediaStore silently uniquifies the rest, which is why the name the entry
     * ended up with is read back rather than assumed.
     */
    private fun freeName(displayName: String): String =
        uniqueDisplayName(safeDisplayName(displayName), ::existsInDownloads)

    private fun existsInDownloads(name: String): Boolean = resolver.query(
        DOWNLOADS,
        arrayOf(MediaStore.Downloads._ID),
        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
        arrayOf(name),
        null,
    )?.use { it.count > 0 } ?: false

    private fun publishedName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(MediaStore.Downloads.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }
}

/** Also what the completion notification asks the system to open the file with. */
internal fun mimeTypeOf(displayName: String): String {
    val extension = displayName.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: DEFAULT_MIME_TYPE
}
