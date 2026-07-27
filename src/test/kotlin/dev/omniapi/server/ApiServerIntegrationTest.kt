package dev.omniapi.server

import burp.api.montoya.MontoyaApi
import dev.omniapi.state.ApiModule
import dev.omniapi.state.ApiState
import io.mockk.every
import io.mockk.mockk
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ApiServerIntegrationTest {
    private var server: ApiServer? = null
    private val client = HttpClient.newHttpClient()

    @AfterTest
    fun tearDown() {
        server?.close()
    }

    @Test
    fun `public documentation assets load while API routes require current key`() {
        val api = mockk<MontoyaApi>(relaxed = true)
        every { api.collaborator().createClient() } returns mockk(relaxed = true)
        val state = ApiState()
        val port = ServerSocket(0).use { it.localPort }
        server = ApiServer(api, state).also { it.start("127.0.0.1", port) }

        assertEquals(200, get(port, "/health").statusCode())
        val swagger = get(port, "/swagger")
        assertEquals(200, swagger.statusCode())
        swaggerAssetPaths(swagger.body()).forEach { assetPath ->
            assertEquals(200, get(port, assetPath).statusCode(), "Swagger asset should be public: $assetPath")
        }
        val openApi = get(port, "/openapi")
        assertEquals(200, openApi.statusCode())
        assertContains(openApi.body(), "/proxy/history")
        assertContains(openApi.body(), "ApiKeyAuth")
        assertContains(openApi.body(), "ApiKeyQueryAuth")
        assertContains(openApi.body(), "\"in\" : \"query\"")

        assertEquals(401, get(port, "/system/capabilities").statusCode())
        assertEquals(200, get(port, "/system/capabilities", state.apiKey()).statusCode())
        assertEquals(200, getWithQueryKey(port, "/system/capabilities", state.apiKey()).statusCode())

        val oldKey = state.apiKey()
        val newKey = state.regenerateKey()
        assertEquals(401, get(port, "/system/capabilities", oldKey).statusCode())
        assertEquals(401, getWithQueryKey(port, "/system/capabilities", oldKey).statusCode())
        assertEquals(200, get(port, "/system/capabilities", newKey).statusCode())
        assertEquals(200, getWithQueryKey(port, "/system/capabilities", newKey).statusCode())
        assertEquals(200, getWithQueryKey(port, "/system/capabilities", newKey, "invalid-header").statusCode())

    }

    @Test
    fun `disabled module is rejected before Montoya handler runs`() {
        val api = mockk<MontoyaApi>(relaxed = true)
        every { api.collaborator().createClient() } returns mockk(relaxed = true)
        val state = ApiState()
        state.setEnabled(ApiModule.PROXY, false)
        val port = ServerSocket(0).use { it.localPort }
        server = ApiServer(api, state).also { it.start("127.0.0.1", port) }

        val response = get(port, "/proxy/history", state.apiKey())
        assertEquals(403, response.statusCode())
        assertContains(response.body(), "MODULE_DISABLED")
    }

    @Test
    fun `unsupported Intruder launch returns stable capability response`() {
        val api = mockk<MontoyaApi>(relaxed = true)
        every { api.collaborator().createClient() } returns mockk(relaxed = true)
        val state = ApiState()
        val port = ServerSocket(0).use { it.localPort }
        server = ApiServer(api, state).also { it.start("127.0.0.1", port) }

        val response = post(
            port,
            "/intruder/attack",
            state.apiKey(),
            """{"host":"example.com","port":443,"secure":true,"requestBase64":"R0VUIC8gSFRUUC8xLjENCkhvc3Q6IGV4YW1wbGUuY29tDQoNCg==","payloads":["one"]}"""
        )
        assertEquals(501, response.statusCode())
        assertContains(response.body(), "MONTOYA_CAPABILITY_UNAVAILABLE")
    }

    @Test
    fun `strict JSON rejects unknown request properties`() {
        val api = mockk<MontoyaApi>(relaxed = true)
        every { api.collaborator().createClient() } returns mockk(relaxed = true)
        val state = ApiState()
        val port = ServerSocket(0).use { it.localPort }
        server = ApiServer(api, state).also { it.start("127.0.0.1", port) }

        val response = post(port, "/proxy/intercept", state.apiKey(), """{"enabled":true,"unexpected":1}""")
        assertEquals(400, response.statusCode())
        assertContains(response.body(), "INVALID_REQUEST")
    }

    private fun get(port: Int, path: String, key: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET()
        key?.let { builder.header("X-API-Key", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun swaggerAssetPaths(html: String): Set<String> =
        Regex("""(?:src|href)=["']([^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { it.startsWith("/webjars/swagger-ui/") }
            .toSet()
            .also { check(it.isNotEmpty()) { "Swagger page did not reference any bundled WebJar assets" } }

    private fun getWithQueryKey(
        port: Int,
        path: String,
        key: String,
        headerKey: String? = null
    ): HttpResponse<String> {
        val separator = if ('?' in path) '&' else '?'
        val encodedKey = URLEncoder.encode(key, Charsets.UTF_8)
        val builder = HttpRequest.newBuilder(
            URI("http://127.0.0.1:$port$path${separator}apiKey=$encodedKey")
        ).GET()
        headerKey?.let { builder.header("X-API-Key", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(port: Int, path: String, key: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("X-API-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
