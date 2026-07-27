package dev.omniapi.handler

import burp.api.montoya.MontoyaApi
import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import dev.omniapi.model.InterceptCommand
import dev.omniapi.model.InterceptStatus
import dev.omniapi.model.HttpMessagePage
import dev.omniapi.montoya.MontoyaMapper
import dev.omniapi.server.CapabilityUnavailable
import dev.omniapi.server.OperationGate
import dev.omniapi.server.bodyValidated
import dev.omniapi.util.Paging
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ProxyHandler(
    private val api: MontoyaApi,
    private val gate: OperationGate
) {
    private val mutationLock = ReentrantLock()

    @OpenApi(
        path = "/proxy/history",
        methods = [HttpMethod.GET],
        summary = "Read Proxy history",
        tags = ["Proxy"],
        queryParams = [
            OpenApiParam(name = "offset", type = Int::class, description = "Zero-based result offset"),
            OpenApiParam(name = "limit", type = Int::class, description = "Page size from 1 to 500"),
            OpenApiParam(name = "urlRegex", type = String::class, description = "RE2 URL filter"),
            OpenApiParam(name = "hostRegex", type = String::class, description = "RE2 host filter")
        ],
        responses = [
            OpenApiResponse(status = "200", content = [OpenApiContent(from = HttpMessagePage::class)]),
            OpenApiResponse(status = "400", content = [OpenApiContent(from = dev.omniapi.model.ErrorResponse::class)]),
            OpenApiResponse(status = "401", content = [OpenApiContent(from = dev.omniapi.model.ErrorResponse::class)]),
            OpenApiResponse(status = "403", content = [OpenApiContent(from = dev.omniapi.model.ErrorResponse::class)])
        ],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun history(ctx: Context) = gate.run {
        val (offset, limit) = Paging.parameters(ctx)
        val urlPattern = compile(ctx.queryParam("urlRegex"), "urlRegex")
        val hostPattern = compile(ctx.queryParam("hostRegex"), "hostRegex")
        val snapshot = api.proxy().history { item ->
            (urlPattern == null || urlPattern.matcher(item.finalRequest().url()).find()) &&
                (hostPattern == null || hostPattern.matcher(item.httpService().host()).find())
        }
        val page = Paging.page(snapshot, offset, limit)
        ctx.json(HttpMessagePage(page.items.map(MontoyaMapper::proxyMessage), page.offset, page.limit, page.returned, page.total))
    }

    @OpenApi(
        path = "/proxy/intercept",
        methods = [HttpMethod.POST],
        summary = "Read or update Proxy interception",
        tags = ["Proxy"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = InterceptCommand::class)], required = false),
        responses = [
            OpenApiResponse(status = "200", content = [OpenApiContent(from = InterceptStatus::class)]),
            OpenApiResponse(status = "501", content = [OpenApiContent(from = dev.omniapi.model.ErrorResponse::class)])
        ],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun intercept(ctx: Context) {
        val command = if (ctx.body().isBlank()) InterceptCommand() else ctx.bodyValidated()
        if (command.action != null || command.messageId != null) {
            throw CapabilityUnavailable(
                "Montoya does not expose Burp's current intercept queue or retroactive forward/drop actions"
            )
        }
        mutationLock.withLock {
            command.enabled?.let {
                if (it) api.proxy().enableIntercept() else api.proxy().disableIntercept()
            }
            ctx.json(
                InterceptStatus(
                    enabled = api.proxy().isInterceptEnabled,
                    limitation = "Montoya does not expose pending intercepted messages"
                )
            )
        }
    }

    private fun compile(value: String?, name: String): Pattern? {
        if (value == null) return null
        if (value.length > 512) throw BadRequestResponse("$name exceeds 512 characters")
        return try { Pattern.compile(value) } catch (e: PatternSyntaxException) {
            throw BadRequestResponse("Invalid $name: ${e.message}")
        }
    }
}
