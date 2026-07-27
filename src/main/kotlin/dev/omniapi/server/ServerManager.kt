package dev.omniapi.server

import burp.api.montoya.MontoyaApi
import burp.api.montoya.collaborator.CollaboratorClient
import dev.omniapi.state.ApiState
import dev.omniapi.state.ServerStatus
import dev.omniapi.util.Validation
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ServerManager(
    private val api: MontoyaApi,
    val state: ApiState
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "omniapi-server-lifecycle").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }
    private val server = AtomicReference<ApiServer?>(null)
    private val listener = AtomicReference<(ServerStatus) -> Unit>({})
    private val collaboratorClient = AtomicReference<CollaboratorClient?>(null)

    fun onStatusChanged(callback: (ServerStatus) -> Unit) {
        listener.set(callback)
    }

    fun startAsync() {
        executeLifecycle("start") {
            if (state.serverStatus.get() in setOf(ServerStatus.RUNNING, ServerStatus.STARTING)) return@executeLifecycle
            transition(ServerStatus.STARTING)
            try {
                val created = ApiServer(api, state, collaboratorClient())
                created.start(state.bindAddress.get(), state.port.get())
                server.set(created)
                state.lastError.set(null)
                transition(ServerStatus.RUNNING)
                api.logging().logToOutput("OmniBridge listening on http://${state.bindAddress.get()}:${state.port.get()}")
            } catch (e: Exception) {
                server.getAndSet(null)?.close()
                state.lastError.set(e.message ?: e.javaClass.simpleName)
                transition(ServerStatus.FAILED)
                api.logging().logToError("OmniBridge failed to start", e)
            }
        }
    }

    fun stopAsync() {
        executeLifecycle("stop") { stopNow() }
    }

    fun restartAsync(bindAddress: String, port: Int) {
        val validatedAddress = Validation.bindAddress(bindAddress)
        val validatedPort = Validation.configuredPort(port)
        executeLifecycle("restart") {
            stopNow()
            state.bindAddress.set(validatedAddress)
            state.port.set(validatedPort)
            startNow()
        }
    }

    override fun close() {
        val task = executor.submit { stopNow() }
        runCatching { task.get(10, TimeUnit.SECONDS) }
        executor.shutdownNow()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private fun startNow() {
        transition(ServerStatus.STARTING)
        try {
            val created = ApiServer(api, state, collaboratorClient())
            created.start(state.bindAddress.get(), state.port.get())
            server.set(created)
            state.lastError.set(null)
            transition(ServerStatus.RUNNING)
            api.logging().logToOutput("OmniBridge listening on http://${state.bindAddress.get()}:${state.port.get()}")
        } catch (e: Exception) {
            server.getAndSet(null)?.close()
            state.lastError.set(e.message ?: e.javaClass.simpleName)
            transition(ServerStatus.FAILED)
            api.logging().logToError("OmniBridge failed to start", e)
        }
    }

    private fun stopNow() {
        if (state.serverStatus.get() == ServerStatus.STOPPED) return
        transition(ServerStatus.STOPPING)
        runCatching { server.getAndSet(null)?.close() }
            .onFailure { api.logging().logToError("OmniBridge failed while stopping", it) }
        transition(ServerStatus.STOPPED)
    }

    private fun transition(status: ServerStatus) {
        state.serverStatus.set(status)
        runCatching { listener.get().invoke(status) }
            .onFailure { api.logging().logToError("OmniBridge status listener failed", it) }
    }

    private fun collaboratorClient(): CollaboratorClient =
        collaboratorClient.get() ?: api.collaborator().createClient().also(collaboratorClient::set)

    private fun executeLifecycle(operation: String, action: () -> Unit) {
        executor.execute {
            try {
                action()
            } catch (e: Throwable) {
                state.lastError.set(e.message ?: e.javaClass.simpleName)
                transition(ServerStatus.FAILED)
                api.logging().logToError("OmniBridge lifecycle operation '$operation' failed", e)
            }
        }
    }
}
