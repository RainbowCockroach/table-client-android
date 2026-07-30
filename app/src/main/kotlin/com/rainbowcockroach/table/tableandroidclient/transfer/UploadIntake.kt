package com.rainbowcockroach.table.tableandroidclient.transfer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import java.io.IOException

/** What came of an intake: [queued] files are in the queue, [rejected] ones never will be. */
data class IntakeResult(val queued: List<String>, val rejected: Int)

/** Turns picked or shared URIs into queued uploads. */
class UploadIntake(
    private val resolver: ContentResolver,
    private val queue: TransferQueue,
    private val staging: UploadStaging,
) {

    suspend fun accept(uris: List<Uri>): IntakeResult {
        val queued = mutableListOf<String>()
        var rejected = 0
        for (uri in uris) {
            val described = runCatching { resolver.describeUpload(uri) }.getOrNull()
            val source = described?.let { runCatching { durableSource(uri) }.getOrNull() }
            if (described == null || source == null) {
                rejected++
                continue
            }
            queue.upload(source, described.name, described.size)
            queued += described.name
        }
        return IntakeResult(queued, rejected)
    }

    /**
     * DESIGN §3 persists the read grant so a retry after process death can still open the
     * source. A share-sheet grant is usually not persistable and is revoked when the
     * receiving activity finishes, so those are copied here instead, while they can still be
     * read at all — the same guarantee, at the cost of one local read.
     */
    private fun durableSource(uri: Uri): String {
        if (persistReadAccess(uri)) return uri.toString()
        val copy = staging.stage {
            resolver.openInputStream(uri) ?: throw IOException("$uri cannot be opened")
        }
        return stagedSourceUri(copy)
    }

    private fun persistReadAccess(uri: Uri): Boolean = runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.isSuccess
}
