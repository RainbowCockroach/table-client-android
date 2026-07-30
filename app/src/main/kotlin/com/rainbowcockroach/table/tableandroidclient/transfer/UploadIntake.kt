package com.rainbowcockroach.table.tableandroidclient.transfer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/** What came of an intake: [queued] files are in the queue, [unreadable] ones never will be. */
data class IntakeResult(val queued: List<String>, val unreadable: Int)

/** Turns picked or shared URIs into queued uploads. */
class UploadIntake(private val resolver: ContentResolver, private val queue: TransferQueue) {

    suspend fun accept(uris: List<Uri>): IntakeResult {
        val queued = mutableListOf<String>()
        var unreadable = 0
        for (uri in uris) {
            val described = runCatching { resolver.describeUpload(uri) }.getOrNull()
            if (described == null) {
                unreadable++
                continue
            }
            persistReadAccess(uri)
            queue.upload(uri.toString(), described.name, described.size)
            queued += described.name
        }
        return IntakeResult(queued, unreadable)
    }

    /** DESIGN §3: without this a retry after process death could no longer open the source. */
    private fun persistReadAccess(uri: Uri) {
        // Share-sheet grants are often not persistable at all; such an upload is then only
        // resumable while this process lives, which beats refusing the file outright.
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
