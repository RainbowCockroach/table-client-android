package com.rainbowcockroach.table.tableandroidclient.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `File` in openapi.yaml. */
@Serializable
data class TableFile(
    val id: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val state: FileState,
    @SerialName("bytes_received") val bytesReceived: Long,
    @SerialName("uploaded_at") val uploadedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
enum class FileState {
    /** Upload still running: live progress in `bytesReceived`, no TTL yet, downloadable via live relay. */
    @SerialName("uploading")
    UPLOADING,

    @SerialName("available")
    AVAILABLE,
}

@Serializable
internal data class UploadCreate(val name: String, val size: Long, val sha256: String)

@Serializable
internal data class UploadCreated(val id: String)

@Serializable
internal data class AckRequest(val sha256: String)

@Serializable
internal data class ApiErrorBody(val error: String)

/** Outcome of `PATCH /uploads/{id}`. */
sealed interface AppendResult {
    /** Final byte accepted: size and hash verified server-side, TTL now running. */
    data class Finalized(val file: TableFile) : AppendResult

    /** Bytes committed, more expected — the request body ended early. */
    data class Incomplete(val committedOffset: Long) : AppendResult

    /** `409`: the offset sent was not the committed one. Resume from [committedOffset]. */
    data class OffsetMismatch(val committedOffset: Long) : AppendResult

    /** `404`: session unknown, aborted, idle-GC'd, or already finalized. */
    data object SessionGone : AppendResult

    /** `422`: declared size/hash did not match. Conformance rule 3 — retry with a NEW session. */
    data class FinalizeRejected(val message: String?) : AppendResult
}

/** Outcome of `POST /files/{id}/ack`. Conformance rules 8-10. */
enum class AckResult {
    /** `204`: hash matched, server deleted the file. */
    DELETED,

    /** `404`/`410`: another device completed it first. Rule 9 — the desired end state holds. */
    ALREADY_GONE,

    /** `409`: the local copy is corrupt. Rule 10 — discard it and re-download. */
    HASH_MISMATCH,
}
