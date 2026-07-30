package com.rainbowcockroach.table.tableandroidclient.api

import org.junit.Test
import kotlin.test.assertFailsWith

/** Conformance rule 13: plain `http://` needs an explicit override. */
class TableClientHostTest {

    @Test
    fun `a plain http host is refused by default`() {
        assertFailsWith<IllegalArgumentException> { TableClient("http://files.example.com", "key") }
    }

    @Test
    fun `a plain http host is accepted once the user overrides`() {
        TableClient("http://127.0.0.1:8080", "key", allowInsecureHttp = true)
    }

    @Test
    fun `https hosts need no override`() {
        TableClient("https://files.example.com", "key")
    }

    @Test
    fun `a trailing slash on the host is tolerated`() {
        TableClient("https://files.example.com/", "key")
    }

    @Test
    fun `a host that is not a url is rejected`() {
        assertFailsWith<IllegalArgumentException> { TableClient("files.example.com", "key") }
    }
}
