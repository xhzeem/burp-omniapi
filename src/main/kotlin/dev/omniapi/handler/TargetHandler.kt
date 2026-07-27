package dev.omniapi.handler

import burp.api.montoya.MontoyaApi
import dev.omniapi.model.ScopeRequest
import dev.omniapi.model.ScopeResponse
import dev.omniapi.model.SitemapPage
import dev.omniapi.montoya.MontoyaMapper
import dev.omniapi.server.OperationGate
import dev.omniapi.server.bodyValidated
import dev.omniapi.util.Paging
import dev.omniapi.util.Validation
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TargetHandler(private val api: MontoyaApi, private val gate: OperationGate) {
    private val scopeLock = ReentrantLock()

    @OpenApi(
        path = "/target/sitemap",
        methods = [HttpMethod.GET],
        summary = "Read the site map",
        tags = ["Target"],
        queryParams = [
            OpenApiParam(name = "offset", type = Int::class, description = "Zero-based result offset"),
            OpenApiParam(name = "limit", type = Int::class, description = "Page size from 1 to 500"),
            OpenApiParam(name = "inScopeOnly", type = Boolean::class, description = "Only return in-scope messages"),
            OpenApiParam(name = "urlPrefix", type = String::class, description = "Native site-map URL prefix filter")
        ],
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = SitemapPage::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun sitemap(ctx: Context) = gate.run {
        val (offset, limit) = Paging.parameters(ctx)
        val inScopeOnly = ctx.queryParam("inScopeOnly")?.let {
            it.toBooleanStrictOrNull() ?: throw BadRequestResponse("inScopeOnly must be true or false")
        } ?: false
        val urlPrefix = ctx.queryParam("urlPrefix")?.also {
            if (it.length > 4096) throw BadRequestResponse("urlPrefix exceeds 4096 characters")
        }
        val snapshot = api.siteMap().requestResponses { node ->
            (urlPrefix == null || node.url().startsWith(urlPrefix)) &&
                (!inScopeOnly || node.requestResponse().request().isInScope)
        }
        val page = Paging.page(snapshot, offset, limit)
        ctx.json(SitemapPage(page.items.map(MontoyaMapper::sitemapItem), page.offset, page.limit, page.returned, page.total))
    }

    @OpenApi(
        path = "/target/scope",
        methods = [HttpMethod.POST],
        summary = "Update suite scope",
        tags = ["Target"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = ScopeRequest::class)]),
        responses = [
            OpenApiResponse(status = "200", content = [OpenApiContent(from = ScopeResponse::class)]),
            OpenApiResponse(status = "400", content = [OpenApiContent(from = dev.omniapi.model.ErrorResponse::class)])
        ],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun scope(ctx: Context) {
        val request = ctx.bodyValidated<ScopeRequest>()
        val targets = Validation.scopeTargets(request.target)
        scopeLock.withLock {
            when (request.action.uppercase()) {
                "ADD", "INCLUDE" -> targets.forEach(api.scope()::includeInScope)
                "REMOVE", "EXCLUDE" -> targets.forEach(api.scope()::excludeFromScope)
                else -> throw BadRequestResponse("action must be ADD or REMOVE")
            }
            ctx.json(ScopeResponse(request.target, targets.all(api.scope()::isInScope), targets))
        }
    }
}
