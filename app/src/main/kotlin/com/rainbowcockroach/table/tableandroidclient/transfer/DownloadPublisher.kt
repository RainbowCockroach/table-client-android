package com.rainbowcockroach.table.tableandroidclient.transfer

import java.io.File

/**
 * Where a download landed: the name it actually got, and — when the destination has one —
 * a URI the completion notification can open it with (DESIGN §4).
 */
data class PublishedDownload(val name: String, val uri: String? = null)

/**
 * The last step of conformance rule 11: move a verified, acked copy somewhere the user can see.
 *
 * An implementation must either publish the whole file or leave nothing behind and throw —
 * [DownloadTask] keeps the temp file until this returns, so a failure costs a retry, never data.
 */
interface DownloadPublisher {
    /** Collision handling may change the name, so the result says where it really went. */
    fun publish(source: File, displayName: String): PublishedDownload
}
