package com.rainbowcockroach.table.tableandroidclient.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {

    @Test
    fun `a higher CI run number is newer`() {
        assertTrue(compareVersions("v1.0.43", "1.0.42")!! > 0)
        assertTrue(compareVersions("v1.0.9", "1.0.10")!! < 0)
        assertEquals(0, compareVersions("v1.0.42", "1.0.42"))
    }

    /** The run number resets when `baseVersionName` is bumped, so the base has to win. */
    @Test
    fun `the marketing version outranks the run number`() {
        assertTrue(compareVersions("v1.1.1", "1.0.99")!! > 0)
        assertTrue(compareVersions("v2.0.1", "1.9.99")!! > 0)
    }

    @Test
    fun `a missing component counts as zero`() {
        assertEquals(0, compareVersions("v1.0", "1.0.0"))
        assertTrue(compareVersions("v1.0.1", "1.0")!! > 0)
        assertTrue(compareVersions("v1.0", "1.0.1")!! < 0)
    }

    @Test
    fun `an unrankable version is not an update`() {
        assertNull(compareVersions("nightly", "1.0.42"))
        assertNull(compareVersions("v1.0.42-rc1", "1.0.42"))
        assertNull(compareVersions("v1.0.43", ""))
    }
}
