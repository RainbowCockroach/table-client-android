package com.rainbowcockroach.table.tableandroidclient.crypto

import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Sha256Test {

    @Test
    fun `hashes the published vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArrayInputStream(ByteArray(0))),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex(ByteArrayInputStream("abc".toByteArray())),
        )
    }

    /** DESIGN §2: a resumed download re-feeds the partial temp file, then continues. */
    @Test
    fun `a digest rebuilt from a partial prefix matches the whole-file digest`() {
        val whole = Random(7).nextBytes(3 * TRANSFER_BUFFER_BYTES + 12345)
        val cut = TRANSFER_BUFFER_BYTES + 999

        val rebuilt = Sha256Hasher()
        rebuilt.updateFrom(ByteArrayInputStream(whole, 0, cut))
        assertEquals(cut.toLong(), rebuilt.bytesHashed)
        rebuilt.updateFrom(ByteArrayInputStream(whole, cut, whole.size - cut))

        assertEquals(sha256Hex(ByteArrayInputStream(whole)), rebuilt.hex())
        assertEquals(whole.size.toLong(), rebuilt.bytesHashed)
    }

    @Test
    fun `hex encoding is lowercase and zero-padded`() {
        assertEquals("000f10ff", byteArrayOf(0, 15, 16, -1).toHex())
    }

    @Test
    fun `sha256 hex recognises only the wire format`() {
        assertTrue("a".repeat(64).isSha256Hex())
        assertFalse("A".repeat(64).isSha256Hex(), "uppercase is not the declared format")
        assertFalse("a".repeat(63).isSha256Hex())
        assertFalse("g".repeat(64).isSha256Hex())
    }
}
