package com.rainbowcockroach.table.tableandroidclient.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.rainbowcockroach.table.tableandroidclient.api.TableFile
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferDirection
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferRecord
import com.rainbowcockroach.table.tableandroidclient.ui.theme.Metrics
import com.rainbowcockroach.table.tableandroidclient.ui.theme.columnSeam
import com.rainbowcockroach.table.tableandroidclient.ui.theme.seam
import com.rainbowcockroach.table.tableandroidclient.ui.theme.tableColors
import com.rainbowcockroach.table.tableandroidclient.ui.theme.well
import kotlinx.coroutines.delay
import java.time.Instant

/** §11.4: the light that turns a border into a cut. */
private const val SEAM_LIGHT_ALPHA = 0.5f

/** The rail takes about a third of a tablet, inside §2's 380–480. */
private const val RAIL_FRACTION = 0.32f

@Composable
fun MainScreen(viewModel: MainViewModel, onOpenSettings: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val list by viewModel.files.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val gone by viewModel.goneDownloads.collectAsStateWithLifecycle()
    val now = tickingClock()
    val context = LocalContext.current
    val handlers = rememberQueueHandlers(context, viewModel)

    PollWhileResumed(settings?.isConfigured == true) { viewModel.refresh() }

    // DESIGN §4: multi-select, and the intake persists the grant so a retry can still read it.
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.upload(uris) }

    Surface(Modifier.fillMaxSize(), color = tableColors.paper) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            // §1: the decision is the width the app actually has — never a device class.
            val available = maxWidth
            val availableHeight = maxHeight
            val wide = available >= Metrics.FlipPoint
            val table: @Composable (Modifier) -> Unit = { modifier ->
                TableColumn(
                    modifier = modifier,
                    configured = settings?.isConfigured == true,
                    ready = settings != null,
                    list = list,
                    transfers = transfers,
                    notice = notice,
                    now = now,
                    medium = wide,
                    onDismissNotice = viewModel::dismissNotice,
                    onIntake = { pickFiles.launch(arrayOf("*/*")) },
                    onOpenSettings = onOpenSettings,
                    onTakeAll = viewModel::downloadAll,
                    onTake = viewModel::download,
                )
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    // §1: at rail width the bars belong to the table's column, not to the window.
                    table(
                        Modifier
                            .weight(1f)
                            .columnSeam(tableColors.line, tableColors.surface.copy(SEAM_LIGHT_ALPHA))
                    )
                    QueueRail(
                        transfers = transfers,
                        gone = gone,
                        handlers = handlers,
                        width = (available * RAIL_FRACTION).coerceIn(Metrics.RailMin, Metrics.RailMax),
                    )
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    table(Modifier.fillMaxSize())
                    // §2: the shelf is absent when the queue is empty, and floats when it is not.
                    if (transfers.isNotEmpty()) {
                        QueueShelf(
                            transfers = transfers,
                            gone = gone,
                            handlers = handlers,
                            available = availableHeight,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableColumn(
    modifier: Modifier,
    configured: Boolean,
    ready: Boolean,
    list: FileListState,
    transfers: List<TransferRecord>,
    notice: String?,
    now: Instant,
    medium: Boolean,
    onDismissNotice: () -> Unit,
    onIntake: () -> Unit,
    onOpenSettings: () -> Unit,
    onTakeAll: () -> Unit,
    onTake: (TableFile) -> Unit,
) = Column(modifier) {
    ActionBar(medium = medium, onIntake = onIntake, onOpenSettings = onOpenSettings)
    // A failed poll stays up until the next one succeeds; a notice says its piece and goes.
    list.error?.let { NoticeLane(it) }
    notice?.let { message ->
        NoticeLane(message)
        LaunchedEffect(message) {
            delay(NOTICE_MILLIS)
            onDismissNotice()
        }
    }
    if (!ready) return@Column
    TableRegion(
        modifier = Modifier.weight(1f).padding(regionPadding(medium)),
        configured = configured,
        list = list,
        transfers = transfers,
        now = now,
        onOpenSettings = onOpenSettings,
        onTakeAll = onTakeAll,
        onTake = onTake,
    )
}

/**
 * `../UI.md` §2's region 1: the intake centred and the only filled button in the app, settings
 * trailing, no wordmark, and — §11.4 — no divider beneath, because the bar is the same surface
 * as the table under it.
 */
@Composable
private fun ActionBar(medium: Boolean, onIntake: () -> Unit, onOpenSettings: () -> Unit) = Box(
    modifier = Modifier
        .fillMaxWidth()
        .height(if (medium) Metrics.ActionBarMedium else Metrics.ActionBarCompact)
        .padding(horizontal = 8.dp),
    contentAlignment = Alignment.Center,
) {
    PillButton(
        text = "Put files on the table",
        onClick = onIntake,
        icon = Glyphs.Plus,
        container = tableColors.rose,
        content = tableColors.onAccent,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        GhostIconButton(Glyphs.Gear, "Settings", onOpenSettings)
    }
}

/** §2's region 2: every error and notice in the app, and nowhere else. */
@Composable
private fun NoticeLane(message: String) = Text(
    text = message,
    style = MaterialTheme.typography.bodySmall,
    color = tableColors.roseText,
    modifier = Modifier
        .fillMaxWidth()
        .background(tableColors.roseTint)
        .seam(tableColors.roseLine, tableColors.surface.copy(SEAM_LIGHT_ALPHA))
        .padding(horizontal = 16.dp, vertical = 8.dp),
)

@Composable
private fun TableRegion(
    modifier: Modifier,
    configured: Boolean,
    list: FileListState,
    transfers: List<TransferRecord>,
    now: Instant,
    onOpenSettings: () -> Unit,
    onTakeAll: () -> Unit,
    onTake: (TableFile) -> Unit,
) = Box(modifier.fillMaxSize().well(tableColors.surface)) {
    // §2: the row column caps at 720 and the margins absorb the rest.
    Column(
        Modifier
            .widthIn(max = Metrics.RowColumnMax)
            .fillMaxSize()
            .align(Alignment.TopCenter)
    ) {
        if (!configured) {
            CentredState(
                title = "No server yet",
                detail = "Add a host URL and API key to see what's on the table.",
            ) {
                PillButton("Open settings", onOpenSettings)
            }
            return@Column
        }
        SectionHeader("On the table", tableColors.roseText) {
            if (list.files.isNotEmpty()) {
                PillButton("Take all", onTakeAll)
            }
        }
        if (list.files.isEmpty()) {
            CentredState(if (list.loaded) "Nothing on the table." else "Loading…")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(list.files, key = { _, file -> file.id }) { index, file ->
                if (index > 0) HorizontalDivider(color = tableColors.divider)
                ServerFileRow(
                    file = file,
                    transfer = transfers.firstOrNull { it.remoteId == file.id },
                    now = now,
                    onTake = { onTake(file) },
                )
            }
        }
    }
}

@Composable
private fun rememberQueueHandlers(context: Context, viewModel: MainViewModel): QueueHandlers {
    // A device with no file manager gets no reveal button rather than a button that fails.
    val folder = remember(context) { downloadsFolderIntent(context) }
    return remember(context, folder, viewModel) {
        val start = { intent: Intent, onFailure: () -> Unit ->
            runCatching { context.startActivity(intent) }.onFailure { onFailure() }
            Unit
        }
        val openOf = { record: TransferRecord ->
            val uri = if (record.direction == TransferDirection.DOWNLOAD) {
                record.publishedUri
            } else {
                record.sourceUri
            }
            uri?.let { openFileIntent(context, it, record.publishedName ?: record.name) }
                ?.let { intent -> { start(intent, viewModel::reportOpenFailed) } }
        }
        QueueHandlers(
            open = openOf,
            // §5: a download reveals the folder it landed in; an upload's source is somewhere
            // only its own app can point at, so that row opens the file instead.
            reveal = { record ->
                if (record.direction == TransferDirection.UPLOAD) {
                    openOf(record)
                } else if (record.publishedUri != null && folder != null) {
                    { start(folder, viewModel::reportRevealFailed) }
                } else {
                    null
                }
            },
            retry = viewModel::retry,
            dismiss = viewModel::dismiss,
            clear = viewModel::dismissFinished,
        )
    }
}

private fun regionPadding(medium: Boolean): PaddingValues = PaddingValues(
    if (medium) Metrics.RegionPaddingMedium else Metrics.RegionPaddingCompact
)

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

private const val NOTICE_MILLIS = 6_000L
