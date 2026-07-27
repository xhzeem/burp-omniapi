package dev.omnibridge.util

import io.javalin.http.BadRequestResponse
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.NetworkInterface

object Validation {
    fun port(value: Int): Int {
        if (value !in 1..65535) throw BadRequestResponse("Port must be between 1 and 65535")
        return value
    }

    fun configuredPort(value: Int): Int {
        if (value !in 1024..65535) throw IllegalArgumentException("Port must be between 1024 and 65535")
        return value
    }

    fun nonBlank(value: String, name: String, maxLength: Int = 8192): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) throw BadRequestResponse("$name must not be blank")
        if (normalized.length > maxLength) throw BadRequestResponse("$name exceeds $maxLength characters")
        return normalized
    }

    fun httpUrl(value: String): String {
        val uri = try { URI(value) } catch (_: URISyntaxException) {
            throw BadRequestResponse("Invalid URL")
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw BadRequestResponse("URL must use http or https and include a host")
        }
        return uri.toASCIIString()
    }

    fun scopeTargets(value: String): List<String> {
        val normalized = nonBlank(value, "target", 4096)
        if (normalized.contains("://")) return listOf(httpUrl(normalized))
        if (normalized.any { it.isWhitespace() || it in "/?#" }) {
            throw BadRequestResponse("A domain target must be a bare hostname")
        }
        val host = try { URI("https://$normalized").host } catch (_: URISyntaxException) { null }
        if (host.isNullOrBlank()) throw BadRequestResponse("Invalid domain target")
        return listOf("http://$host/", "https://$host/")
    }

    fun bindAddress(value: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) throw IllegalArgumentException("Bind address must not be blank")
        val address = InetAddress.getByName(normalized)
        if (!address.isAnyLocalAddress && !address.isLoopbackAddress && NetworkInterface.getByInetAddress(address) == null) {
            throw IllegalArgumentException("Bind address must belong to a local interface or be a wildcard address")
        }
        return address.hostAddress
    }
}
