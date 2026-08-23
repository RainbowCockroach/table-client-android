package com.rainbowcockroach.table.tableandroidclient.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rainbowcockroach.table.tableandroidclient.transfer.TransferRecord
import com.rainbowcockroach.table.tableandroidclient.ui.theme.Metrics
import com.rainbowcockroach.table.tableandroidclient.ui.theme.tableColors
import com.rainbowcockroach.table.tableandroidclient.ui.theme.well

/** §11.1: the shelf is transient, and the only thing in the app that casts a real shadow. */
private val ShelfShadow = 8.dp

private val HandleHeight = 20.dp

/** §11.2: the region ground keeps its corner — here on the edge that faces the table. */
private val railShape = RoundedCornerShape(topStart = Metrics.Corner, bottomStart = Metrics.Corner)

/** What a row's controls do, gathered so the two layouts hand the list the same thing. */
class QueueHandlers(
    val open: (TransferRecord) -> (() -> Unit)?,
    val reveal: (TransferRecord) -> (() -> Unit)?,
    val retry: (String) -> Unit,
    val dismiss: (String) -> Unit,
    val clear: () -> Unit,
)

/**
 * `../UI.md` §1 at or above 900dp: a column on the trailing edge, floor to ceiling, scrolling
 * on its own. §2: it stays when the queue is empty and says so.
 */
@Composable
fun QueueRail(
    transfers: List<TransferRecord>,
    gone: Set<String>,
    handlers: QueueHandlers,
    width: Dp,
    modifier: Modifier = Modifier,
) = Column(
    modifier = modifier
        .width(width)
        .fillMaxHeight()
        .well(tableColors.slateSurface, railShape),
) {
    QueueHeader(transfers, handlers)
    if (transfers.isEmpty()) {
        CentredState("Nothing moving")
    } else {
        QueueList(transfers, gone, handlers, Modifier.fillMaxHeight())
    }
}

/**
 * §1 below 900dp: docked to the bottom edge, floating above the table rather than pushing it,
 * peeking at two rows, draggable to 60% of the window and collapsing to its header.
 *
 * The caller leaves it out entirely when the queue is empty (§2), which is why there is no
 * empty state here.
 */
@Composable
fun QueueShelf(
    transfers: List<TransferRecord>,
    gone: Set<String>,
    handlers: QueueHandlers,
    available: Dp,
    modifier: Modifier = Modifier,
) {
    val collapsed = HandleHeight + Metrics.SectionHeaderHeight
    // A row is its minimum plus the padding it carries, or the second one peeks half-drawn.
    val peek = collapsed + (Metrics.RowMinHeight + 8.dp) * Metrics.ShelfPeekRows
    val tallest = maxOf(available * Metrics.SHELF_MAX_FRACTION, peek)
    var target by remember(tallest) { mutableStateOf(peek.coerceAtMost(tallest)) }
    val height by animateDpAsState(target, label = "shelf")
    val density = LocalDensity.current
    val shape = RoundedCornerShape(topStart = Metrics.Corner, topEnd = Metrics.Corner)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .shadow(ShelfShadow, shape)
            .well(tableColors.slateSurface, shape),
    ) {
        Handle(
            onDrag = { delta ->
                target = (target - with(density) { delta.toDp() }).coerceIn(collapsed, tallest)
            },
            onToggle = { target = if (target <= collapsed) peek.coerceAtMost(tallest) else collapsed },
        )
        QueueHeader(transfers, handlers)
        QueueList(transfers, gone, handlers, Modifier.fillMaxHeight())
    }
}

@Composable
private fun Handle(onDrag: (Float) -> Unit, onToggle: () -> Unit) = Box(
    modifier = Modifier
        .fillMaxWidth()
        .height(HandleHeight)
        .draggable(rememberDraggableState(onDrag), Orientation.Vertical)
        .clickable(onClick = onToggle)
        .semantics { contentDescription = "Resize the transfers shelf" },
    contentAlignment = Alignment.Center,
) {
    Box(
        Modifier
            .size(width = 32.dp, height = 4.dp)
            .background(tableColors.ink3, RoundedCornerShape(2.dp))
    )
}

@Composable
private fun QueueHeader(transfers: List<TransferRecord>, handlers: QueueHandlers) = SectionHeader(
    text = "Transfers",
    color = tableColors.slateText,
) {
    if (transfers.any { it.isFinished }) {
        // §11.2: neutral, because the queue's colour belongs to what is moving, not to the way out.
        PillButton(
            text = "Clear",
            onClick = handlers.clear,
            container = tableColors.divider,
            content = tableColors.ink,
        )
    }
}

@Composable
private fun QueueList(
    transfers: List<TransferRecord>,
    gone: Set<String>,
    handlers: QueueHandlers,
    modifier: Modifier = Modifier,
) = LazyColumn(modifier, contentPadding = PaddingValues(bottom = 8.dp)) {
    itemsIndexed(transfers, key = { _, transfer -> transfer.id }) { index, transfer ->
        // §11.4: rows keep the plain hairline; the seam belongs to region boundaries.
        if (index > 0) HorizontalDivider(color = tableColors.divider)
        TransferRow(
            transfer = transfer,
            gone = transfer.id in gone,
            onOpen = handlers.open(transfer),
            onReveal = handlers.reveal(transfer),
            onRetry = { handlers.retry(transfer.id) },
            onDismiss = { handlers.dismiss(transfer.id) },
        )
    }
}
