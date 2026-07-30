package com.rainbowcockroach.table.tableandroidclient.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/** What a picked or shared URI declares about itself; rule 1 needs the size up front. */
data class UploadFileInfo(val name: String, val size: Long)

/** Null when the provider has nothing to say about the URI, which means it is unusable. */
fun ContentResolver.describeUpload(uri: Uri): UploadFileInfo? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val name = cursor.getString(0) ?: return null
            val size = if (cursor.isNull(1)) return null else cursor.getLong(1)
            UploadFileInfo(name, size).takeIf { it.size > 0 }
        }

class UriUploadSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
) : UploadSource {

    override fun openStream(): InputStream = try {
        resolver.openInputStream(uri) ?: throw SourceUnavailableException("$uri cannot be opened")
    } catch (denied: SecurityException) {
        throw SourceUnavailableException("no longer allowed to read $uri", denied)
    }
}

class ContentUploadSources(private val resolver: ContentResolver) : UploadSources {

    override fun open(record: TransferRecord): UploadSource {
        val uri = Uri.parse(record.sourceUri ?: throw SourceUnavailableException("no source URI"))
        // The size is re-read rather than trusted: it is what the session declares (rule 1),
        // and a provider may hand back a different file than the one that was queued.
        val described = try {
            resolver.describeUpload(uri)
        } catch (denied: SecurityException) {
            throw SourceUnavailableException("no longer allowed to read $uri", denied)
        } ?: throw SourceUnavailableException("$uri is no longer readable")
        return UriUploadSource(resolver, uri, record.name, described.size)
    }
}
