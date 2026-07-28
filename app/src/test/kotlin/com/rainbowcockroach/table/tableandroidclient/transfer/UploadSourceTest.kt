package com.rainbowcockroach.table.tableandroidclient.transfer

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UploadSourceTest {

    /** Content providers can return 0 from `skip` forever; upload resume must still land right. */
    @Test
    fun `skipFully positions a stream that refuses to skip`() {
        val stream = unskippable("0123456789".toByteArray())

        stream.skipFully(4)

        assertEquals('4'.code, stream.read())
    }

    @Test
    fun `skipFully uses a bulk skip when the stream offers one`() {
        val stream = ByteArrayInputStream("0123456789".toByteArray())

        stream.skipFully(7)

        assertEquals('7'.code, stream.read())
    }

    @Test
    fun `skipFully refuses to silently stop short`() {
        assertFailsWith<EOFException> { unskippable("012".toByteArray()).skipFully(9) }
    }

    private fun unskippable(bytes: ByteArray): InputStream = object : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun skip(n: Long): Long = 0
    }
}
