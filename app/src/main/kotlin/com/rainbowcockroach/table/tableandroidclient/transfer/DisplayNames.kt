package com.rainbowcockroach.table.tableandroidclient.transfer

private const val FALLBACK_NAME = "download"
private const val MAX_SUFFIXES = 10_000

/**
 * A server-declared name reduced to something safe as a single file name.
 *
 * The name travels from whatever device uploaded it, so it may hold path separators or
 * control characters that would escape the Downloads directory.
 */
fun safeDisplayName(name: String): String {
    val flattened = name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
        .joinToString("")
        .trim()
        .trimEnd('.')
    return if (flattened.isEmpty() || flattened.all { it == '.' || it == '_' }) FALLBACK_NAME else flattened
}

/**
 * [desired] with ` (n)` inserted before the extension until [isTaken] says no.
 *
 * MediaStore uniquifies colliding display names itself, but only after the insert; resolving
 * the name up front is what lets a download report where it actually landed.
 */
fun uniqueDisplayName(desired: String, isTaken: (String) -> Boolean): String {
    if (!isTaken(desired)) return desired
    val dot = desired.lastIndexOf('.')
    val stem = if (dot > 0) desired.substring(0, dot) else desired
    val extension = if (dot > 0) desired.substring(dot) else ""
    for (n in 1..MAX_SUFFIXES) {
        val candidate = "$stem ($n)$extension"
        if (!isTaken(candidate)) return candidate
    }
    throw IllegalStateException("no free name for $desired after $MAX_SUFFIXES attempts")
}
