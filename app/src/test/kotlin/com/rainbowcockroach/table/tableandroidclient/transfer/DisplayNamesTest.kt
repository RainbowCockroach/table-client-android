package com.rainbowcockroach.table.tableandroidclient.transfer

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayNamesTest {

    @Test
    fun `keeps an ordinary name`() {
        assertEquals("report.pdf", safeDisplayName("report.pdf"))
    }

    @Test
    fun `flattens path separators and control characters`() {
        assertEquals("_etc_passwd", safeDisplayName("/etc/passwd"))
        assertEquals(".._.._boot.img", safeDisplayName("../../boot.img"))
        assertEquals("two_lines", safeDisplayName("two\nlines"))
    }

    @Test
    fun `falls back when nothing usable is left`() {
        assertEquals("download", safeDisplayName(""))
        assertEquals("download", safeDisplayName("   "))
        assertEquals("download", safeDisplayName("///"))
        assertEquals("download", safeDisplayName("."))
    }

    @Test
    fun `leaves an unused name alone`() {
        assertEquals("report.pdf", uniqueDisplayName("report.pdf") { false })
    }

    @Test
    fun `suffixes before the extension`() {
        val taken = setOf("report.pdf", "report (1).pdf")
        assertEquals("report (2).pdf", uniqueDisplayName("report.pdf", taken::contains))
    }

    @Test
    fun `suffixes at the end when there is no extension`() {
        assertEquals("README (1)", uniqueDisplayName("README", setOf("README")::contains))
    }

    @Test
    fun `treats a leading dot as part of the name, not an extension`() {
        assertEquals(".bashrc (1)", uniqueDisplayName(".bashrc", setOf(".bashrc")::contains))
    }
}
