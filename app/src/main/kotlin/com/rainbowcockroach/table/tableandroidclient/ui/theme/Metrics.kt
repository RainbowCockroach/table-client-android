package com.rainbowcockroach.table.tableandroidclient.ui.theme

import androidx.compose.ui.unit.dp

/**
 * `../UI.md` §3, the touch column.
 *
 * Android reports touch even on a tablet with a mouse attached, and §3 says to take the
 * coarser of the two, so the pointer column never applies here.
 */
object Metrics {
    /** §1: shelf below, rail at or above — measured on the width the app actually has. */
    val FlipPoint = 900.dp

    val RowMinHeight = 64.dp
    val ControlMin = 44.dp
    val Glyph = 24.dp
    val SectionHeaderHeight = 52.dp
    val ActionBarCompact = 52.dp
    val ActionBarMedium = 64.dp
    val RegionPaddingCompact = 16.dp
    val RegionPaddingMedium = 24.dp

    /** §2: the margins absorb anything past this, so a tablet row is a phone row. */
    val RowColumnMax = 720.dp
    val RailMin = 380.dp
    val RailMax = 480.dp
    val ShelfPeekRows = 2
    const val SHELF_MAX_FRACTION = 0.6f

    /** §11.2: rows, fields and region grounds keep their corner; buttons are height ÷ 2. */
    val Corner = 6.dp

    /** §11.3: the wall under a button, and the distance it travels when pressed. */
    val Wall = 2.dp

    /** §11.2: circles merge into one shape below this, and the trailing slot is always a pair. */
    val PairedControlGap = 8.dp

    /** §11.4: how deep a region ground is carved, and over how much of its top edge. */
    const val WELL_ALPHA = 0.06f
    val WellDepth = 2.dp
}
