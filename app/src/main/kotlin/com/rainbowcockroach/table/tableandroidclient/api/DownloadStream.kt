package com.rainbowcockroach.table.tableandroidclient.api

import okhttp3.Response
import java.io.Closeable
import java.io.InputStream

private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
private val FILENAME = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)

/**
 * An open download response. The caller owns the connection and must [close] it.
 *
 * A `206` carries only the requested range; [totalSize] is the size of the whole file
 * either way, which is what the length check in conformance rule 6 compares against.
 */
class DownloadStream internal constructor(private val response: Response) : Closeable {

    val statusCode: Int get() = response.code

    /** `X-Checksum-SHA256`: the full-file hash, present on `200` and `206` alike. */
    val checksumSha256: String? = response.header("X-Checksum-SHA256")

    val fileName: String? = response.header("Content-Disposition")
        ?.let { FILENAME.find(it)?.groupValues?.get(1) }

    /** Offset of the first byte in this response body. */
    val rangeStart: Long =
        response.header("Content-Range")?.let { CONTENT_RANGE.find(it)?.groupValues?.get(1)?.toLong() } ?: 0L

    /** Size of the complete file, or null if the server declared neither form of length. */
    val totalSize: Long? =
        response.header("Content-Range")?.let { CONTENT_RANGE.find(it)?.groupValues?.get(3)?.toLong() }
            ?: response.body?.contentLength()?.takeIf { it >= 0 }

    val source: InputStream get() = response.body!!.byteStream()

    override fun close() = response.close()
}
