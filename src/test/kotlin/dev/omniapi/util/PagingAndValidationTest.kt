package dev.omniapi.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PagingAndValidationTest {
    @Test
    fun `page safely handles offsets beyond the collection`() {
        val page = Paging.page(listOf(1, 2, 3), offset = 99, limit = 20)
        assertEquals(emptyList(), page.items)
        assertEquals(3, page.total)
        assertEquals(0, page.returned)
    }

    @Test
    fun `validates configuration ports`() {
        assertEquals(31337, Validation.configuredPort(31337))
        assertFailsWith<IllegalArgumentException> { Validation.configuredPort(1023) }
        assertFailsWith<IllegalArgumentException> { Validation.configuredPort(65536) }
    }

    @Test
    fun `normalizes loopback bind address`() {
        assertEquals("127.0.0.1", Validation.bindAddress("127.0.0.1"))
    }

    @Test
    fun `bare scope domain expands to both HTTP schemes`() {
        assertEquals(
            listOf("http://example.com/", "https://example.com/"),
            Validation.scopeTargets("example.com")
        )
    }
}
