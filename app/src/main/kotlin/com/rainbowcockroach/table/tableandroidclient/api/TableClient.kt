package com.rainbowcockroach.table.tableandroidclient.api

import com.rainbowcockroach.table.tableandroidclient.crypto.isSha256Hex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream
import java.time.Duration

/** The fixed API prefix from openapi.yaml; a host URL never carries it. */
private const val API_PREFIX = "api/v1"

/**
 * No new bytes for this long fails the request. DESIGN §2: transfers get no overall
 * call timeout — a multi-GB file must not race a stopwatch — so socket inactivity is
 * the only watchdog.
 */
private val INACTIVITY_TIMEOUT: Duration = Duration.ofSeconds(60)

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val OFFSET_MEDIA_TYPE = "application/offset+octet-stream".toMediaType()

private val json = Json { ignoreUnknownKeys = true }

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(Duration.ZERO)
    .connectTimeout(Duration.ofSeconds(15))
    .readTimeout(INACTIVITY_TIMEOUT)
    .writeTimeout(INACTIVITY_TIMEOUT)
    .build()

/**
 * Typed wrapper over the table server API.
 *
 * Every method is blocking and safe to call from a background thread; callers own
 * retry and backoff. [openDownload] hands back an open connection the caller must close.
 */
class TableClient(
    hostUrl: String,
    apiKey: String,
    allowInsecureHttp: Boolean = false,
    baseHttpClient: OkHttpClient = defaultHttpClient(),
) {
    private val baseUrl: HttpUrl = parseHostUrl(hostUrl, allowInsecureHttp)

    // Conformance rule 12: the Authorization header is attached here and nowhere else.
    private val http: OkHttpClient = baseHttpClient.newBuilder()
        .addInterceptor(BearerAuthInterceptor(apiKey))
        .build()

    fun listFiles(): List<TableFile> = call(Request.Builder().url(url("files"))).use { response ->
        when (response.code) {
            200 -> json.decodeFromString<List<TableFile>>(response.bodyText())
            else -> throw response.toException("list files")
        }
    }

    /** `POST /uploads`; returns the id used for the file's whole lifecycle. */
    fun createUpload(name: String, size: Long, sha256: String): String {
        require(name.isNotEmpty() && name.length <= 255) { "upload name must be 1-255 characters" }
        require(size > 0) { "upload size must be positive, was $size" }
        require(sha256.isSha256Hex()) { "sha256 must be lowercase hex" }

        val body = json.encodeToString(UploadCreate(name, size, sha256)).toRequestBody(JSON_MEDIA_TYPE)
        return call(Request.Builder().url(url("uploads")).post(body)).use { response ->
            when (response.code) {
                201 -> json.decodeFromString<UploadCreated>(response.bodyText()).id
                else -> throw response.toException("create upload session")
            }
        }
    }

    /** The server's committed offset, or null if the session is unknown. */
    fun uploadOffset(id: String): Long? =
        call(Request.Builder().url(url("uploads", id)).head()).use { response ->
            when (response.code) {
                200 -> response.uploadOffsetHeader()
                    ?: throw TableException("HEAD /uploads/$id returned no Upload-Offset")

                404 -> null
                else -> throw response.toException("read upload offset")
            }
        }

    /**
     * `PATCH /uploads/{id}`: streams [length] bytes from [source], which must already be
     * positioned at [offset]. [onProgress] reports the absolute offset handed to the socket —
     * bytes in flight, not yet committed server-side.
     */
    fun appendBytes(
        id: String,
        offset: Long,
        source: InputStream,
        length: Long,
        onProgress: (Long) -> Unit = {},
    ): AppendResult {
        val body = StreamingRequestBody(OFFSET_MEDIA_TYPE, source, length) { onProgress(offset + it) }
        val request = Request.Builder()
            .url(url("uploads", id))
            .header("Upload-Offset", offset.toString())
            .method("PATCH", body)
        return call(request).use { response ->
            when (response.code) {
                200 -> AppendResult.Finalized(json.decodeFromString(response.bodyText()))
                204 -> AppendResult.Incomplete(response.uploadOffsetHeader() ?: offset)
                404 -> AppendResult.SessionGone
                409 -> AppendResult.OffsetMismatch(response.uploadOffsetHeader() ?: committedOffsetOrThrow(id))
                422 -> AppendResult.FinalizeRejected(response.errorMessage())
                else -> throw response.toException("append upload bytes")
            }
        }
    }

    /** `DELETE /uploads/{id}`; false if the session was already gone. */
    fun abortUpload(id: String): Boolean =
        call(Request.Builder().url(url("uploads", id)).delete()).use { response ->
            when (response.code) {
                204 -> true
                404 -> false
                else -> throw response.toException("abort upload")
            }
        }

    /**
     * Opens `GET /files/{id}`, optionally resuming from [rangeFrom]. The returned stream
     * owns a live connection and must be closed.
     */
    fun openDownload(id: String, rangeFrom: Long = 0L): DownloadStream {
        val request = Request.Builder().url(url("files", id))
        if (rangeFrom > 0) request.header("Range", "bytes=$rangeFrom-")
        val response = call(request)
        return when (response.code) {
            200, 206 -> DownloadStream(response)
            404, 410 -> response.use { throw FileGoneException(id, it.code) }
            416 -> response.use { throw RangeNotSatisfiableException(id) }
            else -> response.use { throw it.toException("download $id") }
        }
    }

    /** `POST /files/{id}/ack` with the hash computed over the complete local copy. */
    fun ack(id: String, sha256: String): AckResult {
        require(sha256.isSha256Hex()) { "sha256 must be lowercase hex" }
        val body = json.encodeToString(AckRequest(sha256)).toRequestBody(JSON_MEDIA_TYPE)
        return call(Request.Builder().url(url("files", id, "ack")).post(body)).use { response ->
            when (response.code) {
                204 -> AckResult.DELETED
                404, 410 -> AckResult.ALREADY_GONE
                409 -> AckResult.HASH_MISMATCH
                else -> throw response.toException("ack $id")
            }
        }
    }

    /** `DELETE /files/{id}`; false if the file was already gone. */
    fun deleteFile(id: String): Boolean =
        call(Request.Builder().url(url("files", id)).delete()).use { response ->
            when (response.code) {
                204 -> true
                404, 410 -> false
                else -> throw response.toException("delete $id")
            }
        }

    private fun committedOffsetOrThrow(id: String): Long =
        uploadOffset(id) ?: throw TableException("upload session $id vanished during offset recovery")

    private fun call(request: Request.Builder): Response = http.newCall(request.build()).execute()

    private fun url(vararg segments: String): HttpUrl =
        baseUrl.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
}

private class BearerAuthInterceptor(apiKey: String) : Interceptor {
    private val value = "Bearer $apiKey"
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("Authorization", value).build())
}

/** Conformance rule 13: plain `http://` is refused unless the user explicitly overrode it. */
private fun parseHostUrl(hostUrl: String, allowInsecureHttp: Boolean): HttpUrl {
    val host = hostUrl.trim().trimEnd('/').removeSuffix("/$API_PREFIX")
    val parsed = host.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("not a valid http(s) URL: $hostUrl")
    require(parsed.isHttps || allowInsecureHttp) {
        "refusing plain http:// host $hostUrl — enable the insecure-host override to use it"
    }
    return parsed.newBuilder().addPathSegments(API_PREFIX).build()
}

private fun Response.uploadOffsetHeader(): Long? = header("Upload-Offset")?.toLongOrNull()

private fun Response.bodyText(): String = body?.string().orEmpty()

private fun Response.errorMessage(): String? = runCatching {
    json.decodeFromString<ApiErrorBody>(bodyText()).error
}.getOrNull()

private fun Response.toException(operation: String): TableException =
    if (code == 401) TableAuthException(operation)
    else TableHttpException(code, errorMessage(), operation)
