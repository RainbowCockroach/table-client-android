package com.rainbowcockroach.table.tableandroidclient.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings

/**
 * `../UI.md` §6's glyphs, mapped once to the Material symbols that mean the same thing —
 * §6 asks each platform to substitute its own system symbol where it has a close equivalent.
 *
 * `check` and `clock` are not here: §6 leaves the countdown as words, and a landed row is
 * already named by its meta line.
 */
object Glyphs {
    val Plus = Icons.Filled.Add
    val Gear = Icons.Filled.Settings
    val Take = Icons.Filled.Download
    val Reveal = Icons.Filled.FolderOpen
    val Open = Icons.Filled.OpenInNew
    val Retry = Icons.Filled.Refresh
    val Dismiss = Icons.Filled.Close
    val Up = Icons.Filled.ArrowUpward
    val Down = Icons.Filled.ArrowDownward
    val File = Icons.AutoMirrored.Filled.InsertDriveFile
}
