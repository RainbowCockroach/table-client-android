package com.rainbowcockroach.table.tableandroidclient.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.rainbowcockroach.table.tableandroidclient.TableApp
import com.rainbowcockroach.table.tableandroidclient.transfer.IntakeResult
import com.rainbowcockroach.table.tableandroidclient.transfer.UploadIntake
import com.rainbowcockroach.table.tableandroidclient.ui.intakeProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DESIGN §4: the share-sheet trampoline — take what was shared, queue it, say so, finish.
 *
 * It finishes only once the intake has secured every source, because the read grant this
 * activity was handed dies with it and nothing could be uploaded afterwards.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent.sharedUris()
        val text = intent.sharedText()
        if (shared.isEmpty() && text == null) {
            finishWith("Nothing to put on the table.")
            return
        }
        val uploads = (application as TableApp).container.uploads
        val offeredName = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ladder(uploads, shared, text, offeredName) }
            finishWith(confirmation(result))
        }
    }

    /**
     * A share offers one item in up to two representations, so rules 16 and 17 both land here:
     * a photo shared with a caption is a photo, and a stream nothing can read falls through to
     * the caption rather than failing the intake. C9 lifts this into [UploadIntake] beside the
     * clipboard's own offer of several representations at once.
     */
    private suspend fun ladder(
        uploads: UploadIntake,
        shared: List<Uri>,
        text: String?,
        offeredName: String?,
    ): IntakeResult {
        val streams = if (shared.isEmpty()) IntakeResult(emptyList(), rejected = 0) else uploads.accept(shared)
        return if (text != null && streams.queued.isEmpty()) uploads.accept(text, offeredName) else streams
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}

private fun confirmation(intake: IntakeResult): String =
    intakeProblem(intake) ?: "Queued ${intake.queued.size} for the table ✓"

private fun Intent.sharedUris(): List<Uri> = when (action) {
    Intent.ACTION_SEND ->
        listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))

    Intent.ACTION_SEND_MULTIPLE ->
        IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()

    else -> emptyList()
}

/** Only the single-item send carries text; no share sheet produces a multi-text send. */
private fun Intent.sharedText(): String? = takeIf { it.action == Intent.ACTION_SEND }
    ?.getCharSequenceExtra(Intent.EXTRA_TEXT)
    ?.toString()
    ?.takeIf { it.isNotBlank() }
