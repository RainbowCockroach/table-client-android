package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.api.isRetryable
import java.io.File

sealed interface DownloadResult {
    /** Verified, acked, and visible to the user under [publishedName]. */
    data class Published(val publishedName: String) : DownloadResult

    data class Failed(val failure: TransferFailure) : DownloadResult
}

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
    ): DownloadResult {
        val tempFile = tempFileFor(target.id)
        return try {
            when (val outcome = Downloader(client).download(target, tempFile, onProgress)) {
                is DownloadOutcome.Verified -> publish(tempFile, target)

                is DownloadOutcome.Incomplete -> retryable(
                    "stopped after ${outcome.bytesOnDisk} of ${target.size} bytes"
                )

                is DownloadOutcome.Corrupt -> retryable("${outcome.reason} — downloading again")

                DownloadOutcome.Gone -> DownloadResult.Failed(
                    TransferFailure("no longer on the server", retryable = false)
                )
            }
        } catch (failure: Exception) {
            DownloadResult.Failed(
                TransferFailure(failure.message ?: failure.toString(), failure.isRetryable)
            )
        }
    }

    /** Rule 11: the temp file outlives the ack and is dropped only once the copy is published. */
    private fun publish(tempFile: File, target: DownloadTarget): DownloadResult {
        val publishedName = publisher.publish(tempFile, target.name)
        tempFile.delete()
        return DownloadResult.Published(publishedName)
    }

    private fun tempFileFor(fileId: String): File {
        tempDir.mkdirs()
        // Named by file id so a retry after process death finds the same partial file (rule 14).
        return File(tempDir, "$fileId.part")
    }
}

private fun retryable(message: String) = DownloadResult.Failed(TransferFailure(message, retryable = true))
