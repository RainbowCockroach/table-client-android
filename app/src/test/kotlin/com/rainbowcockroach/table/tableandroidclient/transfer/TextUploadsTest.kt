package com.rainbowcockroach.table.tableandroidclient.transfer

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Rules 21 and 22: what a piece of text is called and what bytes it becomes. */
class TextUploadsTest {

    private val noon = { LocalDateTime.of(2026, 8, 24, 14, 5, 9) }

    @Test
    fun `takes the first non-empty line`() {
        assertEquals("Milk and bread.txt", textUploadName("\n \nMilk and bread\nand jam", at = noon))
    }

    @Test
    fun `trims a long line to thirty characters`() {
        val line = "the quick brown fox jumps over the lazy dog"

        assertEquals("the quick brown fox jumps over.txt", textUploadName(line, at = noon))
    }

    /** Half a character is not a name; the pair goes or stays whole. */
    @Test
    fun `never cuts a surrogate pair in half`() {
        val name = textUploadName("x".repeat(29) + "😀 tail", at = noon)

        assertEquals("x".repeat(29) + ".txt", name)
    }

    @Test
    fun `sanitises the line the way a download name is sanitised`() {
        assertEquals("_etc_passwd.txt", textUploadName("/etc/passwd", at = noon))
    }

    @Test
    fun `falls back to a timestamp when no line survives`() {
        assertEquals("Text 2026-08-24 14-05-09.txt", textUploadName("///\n...", at = noon))
    }

    @Test
    fun `prefers the name the sender offered`() {
        val name = textUploadName("https://example.com/a", offered = "Example Domain", at = noon)

        assertEquals("Example Domain.txt", name)
    }

    /** A browser that offers an unusable subject still sends a usable URL. */
    @Test
    fun `falls through an unusable offered name`() {
        val name = textUploadName("https://example.com/a", offered = "   ", at = noon)

        assertEquals("https:__example.com_a.txt", name)
    }

    @Test
    fun `writes utf-8 with no byte order mark`() {
        val bytes = textUploadBytes("héllo")

        assertEquals(listOf<Byte>(0x68, 0xC3.toByte(), 0xA9.toByte(), 0x6C, 0x6C, 0x6F), bytes.toList())
    }
}
