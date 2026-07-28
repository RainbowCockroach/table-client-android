package com.rainbowcockroach.table.tableandroidclient.api

import com.rainbowcockroach.table.tableandroidclient.crypto.TRANSFER_BUFFER_BYTES
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.EOFException
import java.io.InputStream

/**
 * Streams exactly [length] bytes from an already-positioned [source].
 *
 * One-shot: the source cannot be rewound, so OkHttp must never replay this body — a
 * retry has to go back through `HEAD` for the committed offset (conformance rule 2).
 */
internal class StreamingRequestBody(
    private val contentType: MediaType,
    private val source: InputStream,
    private val length: Long,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType = contentType

    override fun contentLength(): Long = length

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        var written = 0L
        while (written < length) {
            val wanted = minOf(buffer.size.toLong(), length - written).toInt()
            val read = source.read(buffer, 0, wanted)
            if (read < 0) throw EOFException("source ended after $written of $length bytes")
            sink.write(buffer, 0, read)
            written += read
            onProgress(written)
        }
    }
}
