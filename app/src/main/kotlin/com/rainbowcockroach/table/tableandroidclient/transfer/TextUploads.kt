package com.rainbowcockroach.table.tableandroidclient.transfer

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Root DESIGN §3 rule 22: no colons — Windows forbids them and Finder rewrites them. */
private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")

private const val MAX_NAME_CHARS = 30

/** Rule 21: UTF-8, no BOM, whatever the sender's own encoding was. */
fun textUploadBytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

/**
 * Rule 22: the first non-empty line, trimmed to 30 characters and sanitised.
 *
 * [offered] is a name the sender supplied for the text itself (a share sheet's subject); it is
 * tried first and falls through like any line that survives neither the trim nor the sanitiser.
 */
fun textUploadName(
    text: String,
    offered: String? = null,
    at: () -> LocalDateTime = LocalDateTime::now,
): String {
    val stem = nameFromFirstLine(offered)
        ?: nameFromFirstLine(text)
        ?: "Text ${TIMESTAMP.format(at())}"
    return "$stem.txt"
}

private fun nameFromFirstLine(text: String?): String? = text
    ?.lineSequence()
    ?.firstOrNull { it.isNotBlank() }
    ?.trim()
    ?.let(::clipToNameLength)
    ?.let(::sanitizedName)

private fun clipToNameLength(line: String): String {
    if (line.length <= MAX_NAME_CHARS) return line
    // Cutting between a surrogate pair would leave half a character in the name.
    val end = if (line[MAX_NAME_CHARS - 1].isHighSurrogate()) MAX_NAME_CHARS - 1 else MAX_NAME_CHARS
    return line.take(end).trimEnd()
}
