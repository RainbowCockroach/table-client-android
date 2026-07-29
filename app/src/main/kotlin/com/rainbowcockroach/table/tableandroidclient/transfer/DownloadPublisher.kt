package com.rainbowcockroach.table.tableandroidclient.transfer

import java.io.File

/**
 * The last step of conformance rule 11: move a verified, acked copy somewhere the user can see.
 *
 * An implementation must either publish the whole file or leave nothing behind and throw —
 * [DownloadTask] keeps the temp file until this returns, so a failure costs a retry, never data.
 */
interface DownloadPublisher {
    /** Returns the name the file actually got, which collision handling may have changed. */
    fun publish(source: File, displayName: String): String
}
