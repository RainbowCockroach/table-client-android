package com.rainbowcockroach.table.tableandroidclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rainbowcockroach.table.tableandroidclient.api.FileState
import com.rainbowcockroach.table.tableandroidclient.api.TableFile
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferDirection
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferRecord
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferState
import com.rainbowcockroach.table.tableandroidclient.ui.theme.Metrics
import com.rainbowcockroach.table.tableandroidclient.ui.theme.tableColors
import java.time.Instant

/**
 * `../UI.md` §4: a table row and a transfer row are one skeleton seen from two sides — glyph,
 * name, meta line, progress rule, and a trailing slot that holds at most two controls.
 */
@Composable
fun FileRow(
    glyph: ImageVector,
    glyphTint: Color,
    name: String,
    meta: String,
    modifier: Modifier = Modifier,
    glyphLabel: String? = null,
    ground: Color = Color.Transparent,
    tag: String? = null,
    detail: String? = null,
    progress: Float? = null,
    progressColor: Color = tableColors.rose,
    trackColor: Color = tableColors.line,
    onOpen: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) = Row(
    modifier = modifier
        .fillMaxWidth()
        .background(ground)
        .heightIn(min = Metrics.RowMinHeight)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(glyph, glyphLabel, Modifier.size(Metrics.Glyph), tint = glyphTint)
    Spacer(Modifier.width(12.dp))
    Column(
        modifier = Modifier
            .weight(1f)
            // §4: the row body is the open target, and the reveal beside it is the second choice.
            .let { if (onOpen == null) it else it.clickable(onClick = onOpen) }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = tableColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            tag?.let {
                Spacer(Modifier.width(8.dp))
                Tag(it)
            }
        }
        // §4: one meta line, and it is a line — a wrapped one would make every row a
        // different height and the list stop reading as a list.
        Text(
            text = meta,
            style = MaterialTheme.typography.bodySmall,
            color = tableColors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = tableColors.roseText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        progress?.let {
            LinearProgressIndicator(
                progress = { it },
                color = progressColor,
                trackColor = trackColor,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
    Spacer(Modifier.width(8.dp))
    TrailingSlot(trailing)
}

/** §10: butter says the *server* is filling this file, and the word says it too (rule 18). */
@Composable
private fun Tag(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = tableColors.butterText,
    modifier = Modifier
        .background(tableColors.butterLine, RoundedCornerShape(Metrics.Corner))
        .padding(horizontal = 6.dp, vertical = 2.dp),
)

@Composable
fun ServerFileRow(
    file: TableFile,
    transfer: TransferRecord?,
    now: Instant,
    onTake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val arriving = file.state == FileState.UPLOADING
    val moving = transfer?.takeIf { !it.isFinished }
    val downloading = moving?.takeIf { it.direction == TransferDirection.DOWNLOAD }
    // The server commits `bytes_received` when a PATCH ends, not as it streams (root DESIGN §2),
    // so while this device is the sender its own count is the live one and the listing's is stale.
    val sending = moving?.takeIf { it.direction == TransferDirection.UPLOAD }
    val received = maxOf(file.bytesReceived, sending?.bytesDone ?: 0L)
    FileRow(
        modifier = modifier,
        glyph = Glyphs.File,
        glyphTint = tableColors.ink3,
        name = file.name,
        meta = listOfNotNull(describe(file, now, received), downloading?.let(::label))
            .joinToString(" · "),
        // §10: an arriving row sits on butter ground — a 3px bar could not carry it.
        ground = if (arriving) tableColors.butterTint else Color.Transparent,
        tag = if (arriving) "arriving" else null,
        progress = if (arriving) fraction(received, file.size) else null,
        progressColor = tableColors.butter,
        trackColor = tableColors.butterLine,
    ) {
        // §4: while it is in the queue the transfer row is what says so — taking a file this
        // device is still sending is the same round trip twice.
        if (moving == null) {
            RaisedIconButton(Glyphs.Take, "Take ${file.name}", onTake)
        }
    }
}

@Composable
fun TransferRow(
    transfer: TransferRecord,
    gone: Boolean,
    onOpen: (() -> Unit)?,
    onReveal: (() -> Unit)?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val landed = transfer.state == TransferState.DONE && !gone
    FileRow(
        modifier = modifier,
        glyph = if (transfer.direction == TransferDirection.UPLOAD) Glyphs.Up else Glyphs.Down,
        glyphTint = tableColors.slate,
        glyphLabel = if (transfer.direction == TransferDirection.UPLOAD) "Putting" else "Taking",
        name = transfer.name,
        meta = label(transfer, gone),
        detail = transfer.failure?.message,
        progress = when (transfer.state) {
            TransferState.RUNNING, TransferState.VERIFYING -> fraction(transfer.bytesDone, transfer.size)
            else -> null
        },
        onOpen = onOpen.takeIf { landed },
    ) {
        if (transfer.state == TransferState.FAILED) {
            RaisedIconButton(Glyphs.Retry, "Retry ${transfer.name}", onRetry)
        }
        // §5: the reveal goes away with the file it would show.
        if (landed && onReveal != null) {
            val download = transfer.direction == TransferDirection.DOWNLOAD
            RaisedIconButton(
                icon = if (download) Glyphs.Reveal else Glyphs.Open,
                description = if (download) "Show ${transfer.name} in folder" else "Open ${transfer.name}",
                onClick = onReveal,
            )
        }
        GhostIconButton(
            icon = Glyphs.Dismiss,
            // §4: an unfinished row has nothing to do but stop, and this is the control that does it.
            description = if (transfer.isFinished) "Dismiss ${transfer.name}" else "Stop ${transfer.name}",
            onClick = onDismiss,
        )
    }
}

/** Rule 11: the protocol declares size up front, so a bar is never indeterminate. */
private fun fraction(done: Long, total: Long): Float =
    if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
