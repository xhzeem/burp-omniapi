package dev.omniapi.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.HttpMode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.omniapi.handler.SystemHandler
import dev.omniapi.montoya.MontoyaMapper
import dev.omniapi.state.ApiModule
import dev.omniapi.state.ApiState
import dev.omniapi.util.BinaryCodec
import dev.omniapi.util.Paging
import dev.omniapi.util.Validation
import io.javalin.http.Context
import javax.swing.SwingUtilities

/**
 * Stateless MCP Streamable HTTP endpoint. State that belongs to an operation is represented by
 * explicit OmniBridge task IDs rather than transport sessions.
 */
class McpHandler(
    private val api: MontoyaApi,
    private val state: ApiState,
    private val mapper: ObjectMapper
) {
    fun handle(ctx: Context) {
        val request = runCatching { ctx.bodyAsClass(JsonNode::class.java) }.getOrElse {
            return error(ctx, null, -32700, "Parse error")
        }
        val id = request.get("id")
        val method = request.path("method").asText("")
        ctx.header("Mcp-Protocol-Version", PROTOCOL_VERSION)

        when (method) {
            "initialize" -> result(
                ctx,
                id,
                mapOf(
                    "protocolVersion" to PROTOCOL_VERSION,
                    "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
                    "serverInfo" to mapOf("name" to "burp-omnibridge", "version" to SystemHandler.VERSION),
                    "instructions" to "Use OmniBridge tools only against systems you are authorized to test."
                )
            )
            "notifications/initialized", "notifications/cancelled" -> ctx.status(202)
            "ping" -> result(ctx, id, emptyMap<String, Any>())
            "tools/list" -> result(ctx, id, mapOf("tools" to tools()))
            "tools/call" -> callTool(ctx, id, request.path("params"))
            else -> error(ctx, id, -32601, "Method not found")
        }
    }

    private fun callTool(ctx: Context, id: JsonNode?, params: JsonNode) {
        val name = params.path("name").asText("")
        val arguments = params.path("arguments")
        if (name in configToolNames && !state.configEditingEnabled.get()) {
            return toolError(
                ctx,
                id,
                "Burp configuration editing is disabled in the OmniBridge tab"
            )
        }
        val module = toolModules[name]
        if (module != null && !state.isEnabled(module)) {
            return toolError(ctx, id, "${module.name} is disabled in the OmniBridge tab")
        }

        runCatching {
            when (name) {
                "get_system_info" -> mapOf(
                    "product" to SystemHandler.PRODUCT,
                    "version" to SystemHandler.VERSION,
                    "burpVersion" to api.burpSuite().version().toString(),
                    "modules" to state.moduleSnapshot()
                )
                "output_project_options" -> api.burpSuite().exportProjectOptionsAsJson()
                "output_user_options" -> api.burpSuite().exportUserOptionsAsJson()
                "set_project_options" -> {
                    api.burpSuite().importProjectOptionsFromJson(text(arguments, "json"))
                    mapOf("status" to "PROJECT_OPTIONS_APPLIED")
                }
                "set_user_options" -> {
                    api.burpSuite().importUserOptionsFromJson(text(arguments, "json"))
                    mapOf("status" to "USER_OPTIONS_APPLIED")
                }
                "send_http_request" -> {
                    val request = MontoyaMapper.request(
                        text(arguments, "host", 253),
                        Validation.port(integer(arguments, "port")),
                        arguments.path("secure").asBoolean(false),
                        BinaryCodec.decode(text(arguments, "requestBase64"))
                    )
                    val mode = when (arguments.path("mode").asText("AUTO").uppercase()) {
                        "HTTP_1" -> HttpMode.HTTP_1
                        "HTTP_2" -> HttpMode.HTTP_2
                        "AUTO" -> HttpMode.AUTO
                        else -> throw IllegalArgumentException("mode must be AUTO, HTTP_1, or HTTP_2")
                    }
                    MontoyaMapper.httpMessage(api.http().sendRequest(request, mode))
                }
                "get_proxy_history" -> {
                    val offset = arguments.path("offset").asInt(0)
                    val limit = arguments.path("limit").asInt(100)
                    val history = api.proxy().history()
                    val page = Paging.page(history, offset, limit)
                    mapOf(
                        "items" to page.items.map(MontoyaMapper::proxyMessage),
                        "offset" to page.offset,
                        "limit" to page.limit,
                        "returned" to page.returned,
                        "total" to page.total
                    )
                }
                "open_repeater_tab" -> {
                    val request = MontoyaMapper.request(
                        text(arguments, "host", 253),
                        Validation.port(integer(arguments, "port")),
                        arguments.path("secure").asBoolean(false),
                        BinaryCodec.decode(text(arguments, "requestBase64"))
                    )
                    val tabName = arguments.path("tabName").takeUnless(JsonNode::isMissingNode)?.asText()?.take(200)
                    SwingUtilities.invokeLater {
                        if (tabName.isNullOrBlank()) api.repeater().sendToRepeater(request)
                        else api.repeater().sendToRepeater(request, tabName)
                    }
                    mapOf("status" to "OPENED_IN_REPEATER")
                }
                "open_intruder_tab" -> {
                    val request = MontoyaMapper.request(
                        text(arguments, "host", 253),
                        Validation.port(integer(arguments, "port")),
                        arguments.path("secure").asBoolean(false),
                        BinaryCodec.decode(text(arguments, "requestBase64"))
                    )
                    val tabName = arguments.path("tabName").takeUnless(JsonNode::isMissingNode)?.asText()?.take(200)
                    SwingUtilities.invokeLater {
                        if (tabName.isNullOrBlank()) api.intruder().sendToIntruder(request)
                        else api.intruder().sendToIntruder(request, tabName)
                    }
                    mapOf(
                        "status" to "OPENED_IN_INTRUDER",
                        "limitation" to "Montoya cannot select payloads, set attack type, or launch the attack"
                    )
                }
                else -> return error(ctx, id, -32602, "Unknown tool: $name")
            }
        }.onSuccess { value ->
            val text = if (value is String) value else mapper.writeValueAsString(value)
            result(ctx, id, mapOf("content" to listOf(mapOf("type" to "text", "text" to text))))
        }.onFailure { exception ->
            toolError(ctx, id, exception.message ?: "Tool execution failed")
        }
    }

    private fun tools(): List<Map<String, Any>> = buildList {
        add(tool("get_system_info", "Get Burp, OmniBridge, and enabled-module information."))
        add(
            tool(
                "send_http_request",
                "Send a binary-safe HTTP request through Burp and return its response.",
                requestProperties(includeMode = true)
            )
        )
        add(
            tool(
                "get_proxy_history",
                "Read a page of Burp Proxy HTTP history.",
                mapOf(
                    "offset" to integerProperty("Zero-based offset", 0),
                    "limit" to integerProperty("Page size from 1 to 500", 100)
                )
            )
        )
        add(
            tool(
                "open_repeater_tab",
                "Open a request in a new Burp Repeater tab.",
                requestProperties(includeTabName = true)
            )
        )
        add(
            tool(
                "open_intruder_tab",
                "Open a request in Burp Intruder. This does not start an attack.",
                requestProperties(includeTabName = true)
            )
        )
        if (state.configEditingEnabled.get()) {
            add(
                tool(
                    "output_project_options",
                    "Read Burp project options as JSON. The output can contain sensitive configuration."
                )
            )
            add(
                tool(
                    "output_user_options",
                    "Read Burp user options as JSON. The output can contain sensitive configuration."
                )
            )
            add(
                tool(
                    "set_project_options",
                    "Merge JSON into Burp project options. WARNING: configuration changes can execute code.",
                    jsonProperty()
                )
            )
            add(
                tool(
                    "set_user_options",
                    "Merge JSON into Burp user options. WARNING: configuration changes can execute code.",
                    jsonProperty()
                )
            )
        }
    }

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, Any> = emptyMap()
    ): Map<String, Any> = mapOf(
        "name" to name,
        "description" to description,
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to properties.filterKeys {
                it in setOf("host", "port", "secure", "requestBase64", "json")
            }.keys
        )
    )

    private fun requestProperties(
        includeMode: Boolean = false,
        includeTabName: Boolean = false
    ): Map<String, Any> = buildMap {
        put("host", mapOf("type" to "string", "description" to "Target hostname"))
        put("port", mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535))
        put("secure", mapOf("type" to "boolean", "description" to "Use TLS"))
        put("requestBase64", mapOf("type" to "string", "description" to "RFC 4648 base64 HTTP request bytes"))
        if (includeMode) put("mode", mapOf("type" to "string", "enum" to listOf("AUTO", "HTTP_1", "HTTP_2")))
        if (includeTabName) put("tabName", mapOf("type" to "string"))
    }

    private fun integerProperty(description: String, default: Int): Map<String, Any> =
        mapOf("type" to "integer", "description" to description, "default" to default)

    private fun jsonProperty(): Map<String, Any> = mapOf(
        "json" to mapOf("type" to "string", "description" to "Burp options JSON to merge")
    )

    private fun text(node: JsonNode, name: String, maxLength: Int = 16 * 1024 * 1024): String {
        val value = node.path(name).asText("")
        require(value.isNotBlank()) { "$name is required" }
        require(value.length <= maxLength) { "$name is too long" }
        return value
    }

    private fun integer(node: JsonNode, name: String): Int {
        require(node.has(name) && node.path(name).canConvertToInt()) { "$name must be an integer" }
        return node.path(name).asInt()
    }

    private fun result(ctx: Context, id: JsonNode?, value: Any) {
        ctx.contentType("application/json")
        ctx.json(mapOf("jsonrpc" to "2.0", "id" to id, "result" to value))
    }

    private fun error(ctx: Context, id: JsonNode?, code: Int, message: String) {
        ctx.contentType("application/json")
        ctx.json(mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message)))
    }

    private fun toolError(ctx: Context, id: JsonNode?, message: String) {
        result(
            ctx,
            id,
            mapOf(
                "content" to listOf(mapOf("type" to "text", "text" to message)),
                "isError" to true
            )
        )
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private val toolModules = mapOf(
            "send_http_request" to ApiModule.HTTP,
            "get_proxy_history" to ApiModule.PROXY,
            "open_repeater_tab" to ApiModule.REPEATER,
            "open_intruder_tab" to ApiModule.INTRUDER
        )
        private val configToolNames = setOf(
            "output_project_options",
            "output_user_options",
            "set_project_options",
            "set_user_options"
        )
    }
}
