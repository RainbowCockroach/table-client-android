package com.rainbowcockroach.table.tableandroidclient.ui

import com.rainbowcockroach.table.tableandroidclient.transfer.IntakeResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormatTest {

    private val now = Instant.parse("2026-07-30T12:00:00Z")

    /** Rule 15: an uploading file has no TTL yet, and the list must not invent one. */
    @Test
    fun `a file with no expiry has no countdown`() {
        assertNull(formatExpiry(null, now))
    }

    @Test
    fun `the countdown is minutes and seconds`() {
        assertEquals("expires in 9:59", formatExpiry("2026-07-30T12:09:59Z", now))
        assertEquals("expires in 0:07", formatExpiry("2026-07-30T12:00:07Z", now))
    }

    /** A TTL is configurable server-side, so an hour-long one has to read sensibly too. */
    @Test
    fun `an hour or more gains an hours field`() {
        assertEquals("expires in 1:00:00", formatExpiry("2026-07-30T13:00:00Z", now))
    }

    @Test
    fun `a part second still counts as time left`() {
        assertEquals("expires in 0:01", formatExpiry("2026-07-30T12:00:00.400Z", now))
    }

    @Test
    fun `an expiry already past reads as expiring, not as a negative countdown`() {
        assertEquals("expiring", formatExpiry("2026-07-30T11:59:30Z", now))
    }

    /** Only server time decides anything; an unparseable stamp is a display problem. */
    @Test
    fun `a timestamp that cannot be parsed shows nothing`() {
        assertNull(formatExpiry("soon", now))
    }

    @Test
    fun `an intake that queued everything has nothing to report`() {
        assertNull(intakeProblem(IntakeResult(queued = listOf("a.bin"), rejected = 0)))
    }

    @Test
    fun `an intake names how many it could not add`() {
        assertEquals(
            "Queued 2, couldn't add 1.",
            intakeProblem(IntakeResult(queued = listOf("a.bin", "b.bin"), rejected = 1)),
        )
        assertEquals(
            "Couldn't add 2 file(s).",
            intakeProblem(IntakeResult(queued = emptyList(), rejected = 2)),
        )
    }
}
