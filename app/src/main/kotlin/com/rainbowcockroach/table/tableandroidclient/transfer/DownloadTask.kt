package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.api.isRetryable
import java.io.File

/**
 * The whole of conformance rule 11 for one file: temp file → verify → fsync → ack → publish.
 *
 * One attempt, like [Downloader]: a retry re-enters here and picks up from whatever the temp
 * file holds — partial bytes resume via `Range`, and a complete-but-unpublished copy skips
 * straight back to the ack (rule 9 makes the second ack a no-op).
 */
class DownloadTask(private val tempDir: File, private val publisher: DownloadPublisher) {

    fun run(
        client: TableClient,
        target: DownloadTarget,
        onProgress: (bytesOnDisk: Long) -> Unit = {},
    ): TransferResult {
        val tempFile = tempFileFor(target.id)
        return try {
            when (val outcome = Downloader(client).download(target, tempFile, onProgress)) {
                is DownloadOutcome.Verified -> publish(tempFile, target)

                is DownloadOutcome.Incomplete -> retryable(
                    "stopped after ${outcome.bytesOnDisk} of ${target.size} bytes"
                )

                is DownloadOutcome.Corrupt -> retryable("${outcome.reason} — downloading again")

                DownloadOutcome.Gone -> permanent("no longer on the server")
            }
        } catch (failure: Exception) {
            TransferResult.Failed(
                TransferFailure(failure.message ?: failure.toString(), failure.isRetryable)
            )
        }
    }

    /** Rule 11: the temp file outlives the ack and is dropped only once the copy is published. */
    private fun publish(tempFile: File, target: DownloadTarget): TransferResult {
        val publishedName = publisher.publish(tempFile, target.name)
        tempFile.delete()
        return TransferResult.Done(publishedName)
    }

    private fun tempFileFor(fileId: String): File {
        tempDir.mkdirs()
        // Named by file id so a retry after process death finds the same partial file (rule 14).
        return File(tempDir, "$fileId.part")
    }
}
