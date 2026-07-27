package dev.omnibridge.server

import burp.api.montoya.MontoyaApi
import dev.omnibridge.OmniBridgeUnloadHandler
import dev.omnibridge.state.ApiState
import dev.omnibridge.state.ServerStatus
import io.mockk.every
import io.mockk.mockk
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerManagerTest {
    @Test
    fun `starts and stops asynchronously`() {
        val api = mockk<MontoyaApi>(relaxed = true)
        every { api.collaborator().createClient() } returns mockk(relaxed = true)
        val state = ApiState(initialPort = ServerSocket(0).use { it.localPort })
        val manager = ServerManager(api, state)
        try {
            manager.startAsync()
            eventually { state.serverStatus.get() == ServerStatus.RUNNING }
            manager.stopAsync()
            eventually { state.serverStatus.get() == ServerStatus.STOPPED }
            assertEquals(ServerStatus.STOPPED, state.serverStatus.get())
        } finally {
            manager.close()
        }
    }

    @Test
    fun `unload handler releases listener port and lifecycle thread`() {
        val api = mockk<MontoyaApi>(relaxed = true)
        every { api.collaborator().createClient() } returns mockk(relaxed = true)
        val port = ServerSocket(0).use { it.localPort }
        val state = ApiState(initialPort = port)
        val manager = ServerManager(api, state)
        val unloadHandler = OmniBridgeUnloadHandler(manager)

        manager.startAsync()
        eventually { state.serverStatus.get() == ServerStatus.RUNNING }
        unloadHandler.extensionUnloaded()
        unloadHandler.extensionUnloaded()

        ServerSocket(port).use { rebound ->
            assertEquals(port, rebound.localPort)
        }
        eventually {
            Thread.getAllStackTraces().keys.none { it.isAlive && it.name == "omnibridge-server-lifecycle" }
        }
    }

    private fun eventually(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        check(predicate()) { "Condition was not met before timeout" }
    }
}
