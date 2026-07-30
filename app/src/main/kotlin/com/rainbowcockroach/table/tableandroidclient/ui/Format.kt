package com.rainbowcockroach.table.tableandroidclient.ui

import com.rainbowcockroach.table.tableandroidclient.transfer.IntakeResult
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
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

/**
 * The countdown of DESIGN §5, or null while the server reports no expiry.
 *
 * Root DESIGN §5: `expires_at` is server time and clients only display it — nothing here
 * decides anything, so a device clock that is off shows a wrong number and nothing worse.
 */
fun formatExpiry(expiresAt: String?, now: Instant): String? {
    val expiry = expiresAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
        ?: return null
    val left = Duration.between(now, expiry)
    return if (left.isNegative || left.isZero) "expiring" else "expires in ${clockOf(left)}"
}

/** What an intake has to say for itself; null when every file went into the queue. */
fun intakeProblem(result: IntakeResult): String? = when {
    result.rejected == 0 -> null
    result.queued.isEmpty() -> "Couldn't add ${result.rejected} file(s)."
    else -> "Queued ${result.queued.size}, couldn't add ${result.rejected}."
}

private fun clockOf(left: Duration): String {
    // Rounded up: a file with 900 ms left has not expired, and "0:00" would say it has.
    val seconds = left.plusMillis(999).seconds
    return if (seconds >= 3600) {
        String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    } else {
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
}
