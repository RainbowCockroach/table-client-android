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
import com.rainbowcockroach.table.tableandroidclient.ui.intakeProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DESIGN §4: the share-sheet trampoline — take the URIs, queue them, say so, finish.
 *
 * It finishes only once the intake has secured every source, because the read grant this
 * activity was handed dies with it and nothing could be uploaded afterwards.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent.sharedUris()
        if (shared.isEmpty()) {
            finishWith("Nothing to put on the table.")
            return
        }
        val container = (application as TableApp).container
        lifecycleScope.launch {
            val intake = withContext(Dispatchers.IO) { container.uploads.accept(shared) }
            finishWith(confirmation(intake))
        }
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
