package com.rainbowcockroach.table.tableandroidclient.testsupport

import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import com.rainbowcockroach.table.tableandroidclient.api.defaultHttpClient
import com.rainbowcockroach.table.tableandroidclient.transfer.UploadSource
import okhttp3.Interceptor
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import kotlin.random.Random

/**
 * The local `table-server` the conformance suite runs against (DESIGN §7).
 *
 * Address and key come from `TABLE_URL`/`TABLE_API_KEY`, passed through by the Gradle
 * test task as system properties or inherited from the environment.
 */
object TestServer {
    val url: String? = setting("TABLE_URL")
    val apiKey: String? = setting("TABLE_API_KEY")
    val ttlSeconds: Long? = setting("TABLE_TTL_SECONDS")?.toLongOrNull()

    val missingConfigMessage =
        "no table-server configured — start one and set TABLE_URL and TABLE_API_KEY " +
            "(see table-server/CLAUDE.md 'Dev loop')"

    fun unreachableMessage(cause: Throwable) = "table-server at $url is not reachable: $cause"

    /** Null when the server is configured but not answering, so tests can skip rather than fail. */
    fun clientOrNull(log: RequestLog? = null): TableClient? {
        val host = url ?: return null
        val key = apiKey ?: return null
        val http = log?.let { defaultHttpClient().newBuilder().addInterceptor(it).build() }
            ?: defaultHttpClient()
        return TableClient(host, key, allowInsecureHttp = true, baseHttpClient = http)
    }

    private fun setting(name: String): String? =
        System.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: System.getenv(name)?.takeIf { it.isNotBlank() }
}

/** Records what actually went on the wire — the resume rules are assertions about headers. */
class RequestLog : Interceptor {
    data class Entry(
        val method: String,
        val path: String,
        val range: String?,
        val uploadOffset: String?,
        val status: Int,
    )

    private val recorded: MutableList<Entry> = Collections.synchronizedList(mutableListOf())

    val entries: List<Entry> get() = synchronized(recorded) { recorded.toList() }

    fun clear() = synchronized(recorded) { recorded.clear() }

    fun of(method: String): List<Entry> = entries.filter { it.method == method }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        recorded += Entry(
            method = request.method,
            path = request.url.encodedPath,
            range = request.header("Range"),
            uploadOffset = request.header("Upload-Offset"),
            status = response.code,
        )
        return response
    }
}

fun randomFile(dir: File, name: String, size: Int): File =
    File(dir, name).apply { writeBytes(Random.Default.nextBytes(size)) }

/** A source whose stream dies mid-read — the deterministic stand-in for a dropped upload. */
class FailingUploadSource(
    private val delegate: UploadSource,
    private val failAfterBytes: Long,
) : UploadSource {

    override val name: String get() = delegate.name
    override val size: Long get() = delegate.size

    override fun openStream(): InputStream = object : InputStream() {
        private val inner = delegate.openStream()
        private var delivered = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (delivered >= failAfterBytes) throw IOException("simulated drop after $delivered bytes")
            val capped = minOf(len.toLong(), failAfterBytes - delivered).toInt()
            val read = inner.read(b, off, capped)
            if (read > 0) delivered += read
            return read
        }

        override fun close() = inner.close()
    }
}
