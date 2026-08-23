package com.rainbowcockroach.table.tableandroidclient.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore

/**
 * Whether the copy a download was published as is still in `Download/` (`../UI.md` §5).
 *
 * A landed row holds a `content://` URI, and the user is free to delete what it points at from
 * any file manager; the row must say so rather than offer to reveal nothing.
 */
class PublishedDownloads(private val resolver: ContentResolver) {

    fun exists(publishedUri: String): Boolean = runCatching {
        resolver.query(
            Uri.parse(publishedUri),
            arrayOf(MediaStore.Downloads._ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)
}
