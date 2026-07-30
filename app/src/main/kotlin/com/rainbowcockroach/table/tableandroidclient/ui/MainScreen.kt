package com.rainbowcockroach.table.tableandroidclient.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.rainbowcockroach.table.tableandroidclient.api.FileState
import com.rainbowcockroach.table.tableandroidclient.api.TableFile
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferDirection
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferRecord
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferState
import kotlinx.coroutines.delay
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onOpenSettings: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val list by viewModel.files.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val intakeMessage by viewModel.intakeMessage.collectAsStateWithLifecycle()
    val now = tickingClock()

    PollWhileResumed(settings?.isConfigured == true) { viewModel.refresh() }

    // DESIGN §4: multi-select, and the intake persists the grant so a retry can still read it.
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.upload(uris) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("table") },
                actions = {
                    TextButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Upload") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val current = settings ?: return@Column
            list.error?.let { Banner(it) }
            intakeMessage?.let { message ->
                Banner(message)
                LaunchedEffect(message) {
                    delay(INTAKE_MESSAGE_MILLIS)
                    viewModel.dismissIntakeMessage()
                }
            }
            if (!current.isConfigured) {
                NotConfigured(onOpenSettings)
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ServerHeader(
                        anyFiles = list.files.isNotEmpty(),
                        onDownloadAll = viewModel::downloadAll,
                    )
                }
                if (list.files.isEmpty()) {
                    item { Text(if (list.loaded) "Nothing on the table." else "Loading…") }
                }
                // A downloading file is in both lists, and the ids would collide as keys.
                items(list.files, key = { "server-${it.id}" }) { file ->
                    ServerFileRow(
                        file = file,
                        transfer = transfers.firstOrNull { it.remoteId == file.id },
                        now = now,
                        onDownload = { viewModel.download(file) },
                    )
                }
                if (transfers.isNotEmpty()) {
                    item { HorizontalDivider() }
                    item { SectionHeader("Transfers") }
                    items(transfers, key = { "transfer-${it.id}" }) { transfer ->
                        TransferRow(
                            transfer = transfer,
                            onRetry = { viewModel.retry(transfer.id) },
                            onDismiss = { viewModel.dismiss(transfer.id) },
                        )
                    }
                }
            }
        }
    }
}

/** One second is the resolution of the expiry countdowns, and the list has nothing finer. */
@Composable
private fun tickingClock(): Instant = produceState(Instant.now()) {
    while (true) {
        delay(1_000L)
        value = Instant.now()
    }
}.value

/** DESIGN §5 polls only while the screen is actually in front of the user. */
@Composable
private fun PollWhileResumed(enabled: Boolean, poll: suspend () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                poll()
                delay(LIST_POLL_MILLIS)
            }
        }
    }
}

@Composable
private fun ServerHeader(anyFiles: Boolean, onDownloadAll: () -> Unit) = Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
) {
    SectionHeader("On the server")
    Spacer(Modifier.weight(1f))
    if (anyFiles) {
        TextButton(onClick = onDownloadAll) { Text("Download all") }
    }
}

@Composable
private fun ServerFileRow(
    file: TableFile,
    transfer: TransferRecord?,
    now: Instant,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(describe(file, now), style = MaterialTheme.typography.bodySmall)
            // Conformance rule 15: an uploading file shows live progress and downloads anyway.
            if (file.state == FileState.UPLOADING) {
                Progress(file.bytesReceived, file.size)
            }
        }
        Spacer(Modifier.width(12.dp))
        val downloading = transfer?.takeIf { it.direction == TransferDirection.DOWNLOAD }
        if (downloading == null || downloading.state == TransferState.FAILED) {
            Button(onClick = onDownload) { Text("Download") }
        } else {
            Text(label(downloading), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TransferRow(transfer: TransferRecord, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "${arrow(transfer.direction)} ${transfer.name}",
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(label(transfer), style = MaterialTheme.typography.bodySmall)
        if (transfer.state == TransferState.RUNNING || transfer.state == TransferState.VERIFYING) {
            Progress(transfer.bytesDone, transfer.size)
        }
        transfer.failure?.let { failure ->
            Text(failure.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (transfer.state == TransferState.FAILED || transfer.state == TransferState.DONE) {
            Row {
                if (transfer.state == TransferState.FAILED) {
                    TextButton(onClick = onRetry) { Text("Retry now") }
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun Progress(done: Long, total: Long) {
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
}

@Composable
private fun SectionHeader(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Banner(message: String) = Text(
    text = message,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onErrorContainer,
    modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.errorContainer)
        .padding(horizontal = 16.dp, vertical = 8.dp),
)

@Composable
private fun NotConfigured(onOpenSettings: () -> Unit) = Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    Text("No server yet.", style = MaterialTheme.typography.titleMedium)
    Text("Add a host URL and API key to see what's on the table.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    Button(onClick = onOpenSettings) { Text("Open settings") }
}

private const val INTAKE_MESSAGE_MILLIS = 6_000L

private fun arrow(direction: TransferDirection) =
    if (direction == TransferDirection.UPLOAD) "↑" else "↓"

private fun describe(file: TableFile, now: Instant): String = when (file.state) {
    // Rule 15: no TTL until the upload finalizes, so there is nothing to count down yet.
    FileState.UPLOADING -> "${formatBytes(file.bytesReceived)} of ${formatBytes(file.size)} · uploading"
    FileState.AVAILABLE -> listOfNotNull(
        formatBytes(file.size),
        formatExpiry(file.expiresAt, now),
    ).joinToString(" · ")
}

private fun label(transfer: TransferRecord): String = when (transfer.state) {
    TransferState.QUEUED -> "Queued"
    TransferState.RUNNING -> "${formatBytes(transfer.bytesDone)} of ${formatBytes(transfer.size)}"
    TransferState.VERIFYING ->
        if (transfer.direction == TransferDirection.UPLOAD) "Finishing" else "Verifying"

    TransferState.DONE -> transfer.publishedName?.let { "Saved to Downloads as $it" } ?: "Sent"
    // WorkManager owns the retry; the button is only for someone who would rather not wait.
    TransferState.FAILED -> if (transfer.failure?.retryable == true) "Retrying soon" else "Failed"
}
