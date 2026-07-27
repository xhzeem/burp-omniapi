package dev.omnibridge.server

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import java.util.concurrent.Semaphore

inline fun <reified T : Any> Context.bodyValidated(): T = try {
    bodyAsClass(T::class.java)
} catch (e: Exception) {
    throw BadRequestResponse("Invalid JSON request: ${e.message?.substringBefore('\n') ?: "unable to parse body"}")
}

class OperationGate(maxConcurrent: Int = 32) {
    private val semaphore = Semaphore(maxConcurrent, true)

    fun <T> run(block: () -> T): T {
        if (!semaphore.tryAcquire()) throw TooBusy("The server is at its concurrent operation limit")
        return try { block() } finally { semaphore.release() }
    }
}
