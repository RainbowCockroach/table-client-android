package com.rainbowcockroach.table.tableandroidclient.ui

import com.rainbowcockroach.table.tableandroidclient.api.FileState
import com.rainbowcockroach.table.tableandroidclient.api.TableFile
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class MetaLinesTest {

    private val now = Instant.parse("2026-07-30T12:00:00Z")

    private val arriving = TableFile(
        id = "f1",
        name = "clip.mp4",
        size = 7_900_000L,
        sha256 = "0".repeat(64),
        state = FileState.UPLOADING,
        bytesReceived = 0L,
    )

    @Test
    fun `an arriving file reads as bytes of total`() {
        assertEquals(
            "3.1 MB of 7.9 MB · uploading",
            describe(arriving.copy(bytesReceived = 3_100_000L), now),
        )
    }

    /**
     * The server commits `bytes_received` when a PATCH ends, not as it streams (root DESIGN §2):
     * this device's own upload would otherwise read `0 B` for the whole send.
     */
    @Test
    fun `a fresher count than the listing's wins`() {
        assertEquals("3.1 MB of 7.9 MB · uploading", describe(arriving, now, 3_100_000L))
    }

    @Test
    fun `a file on the table reads as size and countdown`() {
        val available = arriving.copy(
            state = FileState.AVAILABLE,
            expiresAt = "2026-07-30T12:07:12Z",
        )
        assertEquals("7.9 MB · expires in 7:12", describe(available, now))
    }
}
