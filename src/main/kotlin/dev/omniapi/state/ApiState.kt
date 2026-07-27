package dev.omniapi.state

import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class ApiModule(val path: String) {
    PROXY("/proxy"), TARGET("/target"), REPEATER("/repeater"),
    SCANNER("/scanner"), INTRUDER("/intruder"), COLLABORATOR("/collaborator"),
    HTTP("/http"), WEBSOCKETS("/websockets"), TOOLS("/tools"),
    BAMBDAS("/bambda"), UTILITIES("/utilities");

    companion object {
        fun forPath(path: String): ApiModule? = entries.firstOrNull {
            path == it.path || path.startsWith("${it.path}/")
        }
    }
}

enum class ServerStatus { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

class ApiState(initialPort: Int = 31337, initialBindAddress: String = "127.0.0.1") {
    private val random = SecureRandom()
    private val key = AtomicReference(generateKey())
    private val modules = EnumMap<ApiModule, AtomicBoolean>(ApiModule::class.java)
    val port = AtomicInteger(initialPort)
    val bindAddress = AtomicReference(initialBindAddress)
    val restEnabled = AtomicBoolean(true)
    val mcpEnabled = AtomicBoolean(true)
    val configEditingEnabled = AtomicBoolean(false)
    val serverStatus = AtomicReference(ServerStatus.STOPPED)
    val lastError = AtomicReference<String?>(null)

    init { ApiModule.entries.forEach { modules[it] = AtomicBoolean(true) } }

    fun apiKey(): String = key.get()
    fun regenerateKey(): String = generateKey().also(key::set)
    fun apiKeyMatches(candidate: String?): Boolean {
        if (candidate == null) return false
        if (candidate.length != apiKey().length) return false
        return MessageDigest.isEqual(
            apiKey().toByteArray(Charsets.US_ASCII),
            candidate.toByteArray(Charsets.US_ASCII)
        )
    }
    fun isEnabled(module: ApiModule): Boolean = modules.getValue(module).get()
    fun setEnabled(module: ApiModule, enabled: Boolean) = modules.getValue(module).set(enabled)
    fun moduleSnapshot(): Map<String, Boolean> = ApiModule.entries.associate { it.name to isEnabled(it) }

    private fun generateKey(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
