package dev.omniapi.server

import burp.api.montoya.MontoyaApi
import burp.api.montoya.collaborator.CollaboratorClient
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import dev.omniapi.handler.BambdaHandler
import dev.omniapi.handler.CollaboratorHandler
import dev.omniapi.handler.HttpHandler
import dev.omniapi.handler.IntruderHandler
import dev.omniapi.handler.ProxyHandler
import dev.omniapi.handler.RepeaterHandler
import dev.omniapi.handler.ScannerHandler
import dev.omniapi.handler.SystemHandler
import dev.omniapi.handler.TargetHandler
import dev.omniapi.handler.ToolsHandler
import dev.omniapi.handler.UtilitiesHandler
import dev.omniapi.handler.WebSocketHandler
import dev.omniapi.model.ErrorResponse
import dev.omniapi.state.ApiModule
import dev.omniapi.state.ApiState
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.delete
import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.post
import io.javalin.apibuilder.ApiBuilder.put
import io.javalin.http.BadRequestResponse
import io.javalin.http.HttpResponseException
import io.javalin.json.JavalinJackson
import io.javalin.openapi.OpenApiInfo
import io.javalin.openapi.plugin.OpenApiPlugin
import io.javalin.openapi.plugin.swagger.SwaggerPlugin
import java.util.UUID

class ApiServer(
    private val api: MontoyaApi,
    private val state: ApiState,
    private val collaboratorClient: CollaboratorClient = api.collaborator().createClient()
) : AutoCloseable {
    private val gate = OperationGate()
    private val scanner = ScannerHandler(api, gate)
    private val webSockets = WebSocketHandler(api, gate)
    private var app: Javalin? = null

    fun start(host: String, port: Int) {
        check(app == null) { "Server is already started" }
        val system = SystemHandler(api, state)
        val proxy = ProxyHandler(api, gate)
        val target = TargetHandler(api, gate)
        val repeater = RepeaterHandler(api)
        val intruder = IntruderHandler(api)
        val collaborator = CollaboratorHandler(collaboratorClient, gate)
        val http = HttpHandler(api, gate)
        val tools = ToolsHandler(api, gate)
        val bambda = BambdaHandler(api)
        val utilities = UtilitiesHandler(api, gate)

        val mapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

        val created = Javalin.create { config ->
            config.showJavalinBanner = false
            config.http.maxRequestSize = MAX_REQUEST_SIZE
            config.jsonMapper(JavalinJackson(mapper))
            config.registerPlugin(OpenApiPlugin { plugin ->
                plugin.withDocumentationPath("/openapi")
                    .withDefinitionConfiguration { _, definition ->
                        definition.withInfo { info: OpenApiInfo ->
                            info.title = "Burp OmniAPI"
                            info.version = SystemHandler.VERSION
                            info.description = "Authenticated workflow API for Burp Suite's public Montoya capabilities"
                        }
                        definition.withSecurity {
                            it.withApiKeyAuth("ApiKeyAuth", "X-API-Key")
                            it.withApiKeyAuth("ApiKeyQueryAuth", API_KEY_QUERY_PARAMETER) { scheme ->
                                scheme.`in` = "query"
                            }
                        }
                    }
            })
            config.registerPlugin(SwaggerPlugin { swagger ->
                swagger.documentationPath = "/openapi"
                swagger.uiPath = "/swagger"
            })
            config.router.apiBuilder {
                get("/health", system::health)
                get("/system/info", system::info)
                get("/system/capabilities", system::capabilities)

                get("/proxy/history", proxy::history)
                post("/proxy/intercept", proxy::intercept)

                get("/target/sitemap", target::sitemap)
                post("/target/scope", target::scope)

                post("/repeater/send", repeater::send)

                post("/scanner/scan", scanner::scan)
                get("/scanner/tasks/{id}", scanner::task)
                get("/scanner/issues", scanner::issues)

                post("/intruder/attack", intruder::attack)

                post("/collaborator/payload", collaborator::payload)
                get("/collaborator/interactions", collaborator::interactions)

                post("/http/send", http::send)
                get("/http/cookies", http::cookies)
                put("/http/cookies", http::setCookie)

                post("/websockets", webSockets::connect)
                post("/websockets/{id}/messages", webSockets::send)
                get("/websockets/{id}/events", webSockets::events)
                delete("/websockets/{id}", webSockets::close)

                post("/tools/decoder", tools::decoder)
                post("/tools/comparer", tools::comparer)
                post("/tools/organizer", tools::organizer)
                get("/tools/organizer", tools::organizerItems)

                post("/bambda/import", bambda::importBambda)
                post("/utilities/transform", utilities::transform)
            }
        }

        created.before { ctx ->
            val requestId = UUID.randomUUID().toString()
            ctx.attribute(REQUEST_ID, requestId)
            ctx.header("X-Request-ID", requestId)
        }
        created.beforeMatched { ctx ->
            if (isPublic(ctx.path())) return@beforeMatched
            val headerMatches = state.apiKeyMatches(ctx.header(API_KEY_HEADER))
            val queryMatches = state.apiKeyMatches(ctx.queryParam(API_KEY_QUERY_PARAMETER))
            if (!headerMatches && !queryMatches) {
                ctx.status(401).json(
                    error(
                        ctx,
                        "UNAUTHORIZED",
                        "A valid X-API-Key header or apiKey query parameter is required"
                    )
                )
                ctx.skipRemainingHandlers()
                return@beforeMatched
            }
            ApiModule.forPath(ctx.path())?.let { module ->
                if (!state.isEnabled(module)) {
                    ctx.status(403).json(error(ctx, "MODULE_DISABLED", "${module.name} is disabled in the OmniAPI tab"))
                    ctx.skipRemainingHandlers()
                }
            }
        }
        created.exception(CapabilityUnavailable::class.java) { exception, ctx ->
            ctx.status(501).json(error(ctx, "MONTOYA_CAPABILITY_UNAVAILABLE", exception.message ?: "Capability unavailable"))
        }
        created.exception(TooBusy::class.java) { exception, ctx ->
            ctx.status(429).json(error(ctx, "TOO_MANY_OPERATIONS", exception.message ?: "Too many operations"))
        }
        created.exception(BadRequestResponse::class.java) { exception, ctx ->
            ctx.status(400).json(error(ctx, "INVALID_REQUEST", exception.message ?: "Invalid request"))
        }
        created.exception(IllegalArgumentException::class.java) { exception, ctx ->
            ctx.status(400).json(error(ctx, "INVALID_REQUEST", exception.message ?: "Invalid request"))
        }
        created.exception(HttpResponseException::class.java) { exception, ctx ->
            ctx.status(exception.status).json(error(ctx, "HTTP_${exception.status}", exception.message ?: "Request failed"))
        }
        created.exception(Exception::class.java) { exception, ctx ->
            api.logging().logToError("OmniAPI request ${ctx.attribute<String>(REQUEST_ID)} failed", exception)
            ctx.status(500).json(error(ctx, "INTERNAL_ERROR", "The request failed; see Burp's extension log"))
        }
        created.error(404) { ctx ->
            ctx.json(error(ctx, "NOT_FOUND", "No OmniAPI endpoint matches this request"))
        }

        try {
            created.start(host, port)
            app = created
        } catch (e: Exception) {
            runCatching { created.stop() }
            throw e
        }
    }

    fun localPort(): Int = app?.port() ?: throw IllegalStateException("Server is not running")

    override fun close() {
        webSockets.closeAll()
        scanner.close()
        app?.stop()
        app = null
    }

    private fun isPublic(path: String): Boolean =
        path == "/health" ||
            path == "/openapi" ||
            path.startsWith("/openapi/") ||
            path == "/swagger" ||
            path.startsWith("/swagger/") ||
            path.startsWith("/webjars/swagger-ui/")

    private fun error(ctx: io.javalin.http.Context, code: String, message: String) =
        ErrorResponse(code, message, ctx.attribute(REQUEST_ID))

    companion object {
        private const val REQUEST_ID = "omniapi.requestId"
        private const val API_KEY_HEADER = "X-API-Key"
        private const val API_KEY_QUERY_PARAMETER = "apiKey"
        private const val MAX_REQUEST_SIZE = 16L * 1024 * 1024
    }
}
