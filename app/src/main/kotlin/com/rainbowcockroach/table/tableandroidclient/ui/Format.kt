package com.rainbowcockroach.table.tableandroidclient.ui

import java.util.Locale

private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

/** Short, one-decimal byte sizes for list rows: `1.4 MB`. */
fun formatBytes(bytes: Long): String {
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < UNITS.lastIndex) {
        value /= 1000
        unit++
    }
    val decimals = if (unit == 0 || value >= 100) 0 else 1
    return String.format(Locale.US, "%.${decimals}f %s", value, UNITS[unit])
}
