package dev.omniapi.state

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApiStateTest {
    @Test
    fun `uses requested safe defaults`() {
        val state = ApiState()
        assertEquals(31337, state.port.get())
        assertEquals("127.0.0.1", state.bindAddress.get())
        assertTrue(state.restEnabled.get())
        assertTrue(state.mcpEnabled.get())
        assertFalse(state.configEditingEnabled.get())
        assertEquals(32, Base64.getUrlDecoder().decode(state.apiKey()).size)
        assertTrue(ApiModule.entries.all(state::isEnabled))
    }

    @Test
    fun `regeneration immediately invalidates old key`() {
        val state = ApiState()
        val old = state.apiKey()
        val replacement = state.regenerateKey()
        assertNotEquals(old, replacement)
        assertFalse(state.apiKeyMatches(old))
        assertTrue(state.apiKeyMatches(replacement))
        assertFalse(state.apiKeyMatches(null))
    }

    @Test
    fun `module mapping respects path boundaries`() {
        assertEquals(ApiModule.PROXY, ApiModule.forPath("/proxy/history"))
        assertEquals(ApiModule.BAMBDAS, ApiModule.forPath("/bambda/import"))
        assertEquals(null, ApiModule.forPath("/proxying"))
    }
}
