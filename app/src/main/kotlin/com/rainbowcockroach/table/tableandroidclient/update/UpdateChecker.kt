package com.rainbowcockroach.table.tableandroidclient.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration

/** The one page the prompt sends the user to; the APK to download is attached to its releases. */
const val RELEASES_PAGE_URL = "https://github.com/RainbowCockroach/table-client-android/releases"

private const val LATEST_RELEASE_URL =
    "https://api.github.com/repos/RainbowCockroach/table-client-android/releases/latest"

/**
 * Unlike a transfer, this is a fixed-size request behind a button: it either answers while the
 * user is still looking at the screen or the check has failed.
 */
private val CHECK_TIMEOUT: Duration = Duration.ofSeconds(15)

private val json = Json { ignoreUnknownKeys = true }

sealed interface UpdateStatus {
    data object Checking : UpdateStatus
    data class UpToDate(val version: String) : UpdateStatus
    data class Available(val version: String) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

@Serializable
private data class LatestRelease(@SerialName("tag_name") val tagName: String)

/**
 * Compares the newest published GitHub release with the installed build. DESIGN §4: the check
 * is unauthenticated and never touches the table server or its API key.
 *
 * Blocking; call from a background thread.
 */
class UpdateChecker(
    val installedVersion: String,
    baseHttpClient: OkHttpClient = OkHttpClient(),
) {
    private val http = baseHttpClient.newBuilder().callTimeout(CHECK_TIMEOUT).build()

    fun check(): UpdateStatus = try {
        val tag = fetchLatestTag()
        val order = compareVersions(tag, installedVersion)
        when {
            order == null -> UpdateStatus.Failed(
                "GitHub's latest release is tagged $tag, which cannot be ranked against $installedVersion."
            )

            order > 0 -> UpdateStatus.Available(tag.removePrefix("v"))
            else -> UpdateStatus.UpToDate(installedVersion)
        }
    } catch (e: IOException) {
        UpdateStatus.Failed(e.message ?: e.toString())
    } catch (e: RuntimeException) {
        // A body that is not the release JSON the API documents, most likely a proxy or a
        // captive portal answering instead of GitHub.
        UpdateStatus.Failed("Unexpected reply from GitHub: ${e.message ?: e.toString()}")
    }

    private fun fetchLatestTag(): String {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}")
            json.decodeFromString<LatestRelease>(body).tagName
        }
    }
}

/**
 * Ranks two `<base>.<build>` versions component-wise, a leading `v` optional on either.
 * Null when a side is not a dotted number: a version this build cannot rank is not an update.
 */
internal fun compareVersions(a: String, b: String): Int? {
    val left = versionParts(a) ?: return null
    val right = versionParts(b) ?: return null
    for (i in 0 until maxOf(left.size, right.size)) {
        val order = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
        if (order != 0) return order
    }
    return 0
}

private fun versionParts(version: String): List<Int>? =
    version.trim().removePrefix("v")
        .split('.')
        .map { part -> part.toIntOrNull() ?: return null }
        .takeIf { it.isNotEmpty() }
