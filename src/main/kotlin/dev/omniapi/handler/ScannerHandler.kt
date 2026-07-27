package dev.omniapi.handler

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.scanner.AuditConfiguration
import burp.api.montoya.scanner.BuiltInAuditConfiguration
import burp.api.montoya.scanner.CrawlConfiguration
import burp.api.montoya.scanner.ScanTask
import burp.api.montoya.scanner.audit.Audit
import burp.api.montoya.sitemap.SiteMapFilter
import dev.omniapi.model.IssueDto
import dev.omniapi.model.IssuePage
import dev.omniapi.model.ScanRequest
import dev.omniapi.model.ScanResponse
import dev.omniapi.model.ScanTaskDto
import dev.omniapi.montoya.MontoyaMapper
import dev.omniapi.server.CapabilityUnavailable
import dev.omniapi.server.OperationGate
import dev.omniapi.server.bodyValidated
import dev.omniapi.util.Paging
import dev.omniapi.util.Validation
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import io.javalin.openapi.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ScannerHandler(private val api: MontoyaApi, private val gate: OperationGate) {
    private data class ManagedTask(val id: String, val mode: String, val task: ScanTask)
    private val tasks = ConcurrentHashMap<String, ManagedTask>()

    @OpenApi(
        path = "/scanner/scan",
        methods = [HttpMethod.POST],
        summary = "Start a crawl or active audit",
        tags = ["Scanner"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = ScanRequest::class)]),
        responses = [
            OpenApiResponse(status = "202", content = [OpenApiContent(from = ScanResponse::class)]),
            OpenApiResponse(status = "400", content = [OpenApiContent(from = dev.omniapi.model.ErrorResponse::class)]),
            OpenApiResponse(status = "501", content = [OpenApiContent(from = dev.omniapi.model.ErrorResponse::class)])
        ],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun scan(ctx: Context) = gate.run {
        if (tasks.size >= MAX_TASKS) {
            throw dev.omniapi.server.TooBusy("The scanner task registry is full; restart OmniAPI to clear it")
        }
        val input = ctx.bodyValidated<ScanRequest>()
        if (input.urls.isEmpty() || input.urls.size > 100) {
            throw BadRequestResponse("urls must contain between 1 and 100 entries")
        }
        val urls = input.urls.map(Validation::httpUrl)
        val mode = input.mode.uppercase()
        if (mode == "PASSIVE") {
            throw CapabilityUnavailable("Montoya registers passive checks but cannot force Burp to passively scan a request")
        }
        val task: ScanTask = when (mode) {
            "CRAWL" -> api.scanner().startCrawl(CrawlConfiguration.crawlConfiguration(*urls.toTypedArray()))
            "ACTIVE", "AUDIT" -> {
                if (input.configuration.uppercase() != "LEGACY_ACTIVE_AUDIT_CHECKS") {
                    throw BadRequestResponse("configuration must be LEGACY_ACTIVE_AUDIT_CHECKS")
                }
                api.scanner().startAudit(
                    AuditConfiguration.auditConfiguration(BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS)
                ).also { audit -> urls.forEach { audit.addRequest(HttpRequest.httpRequestFromUrl(it)) } }
            }
            else -> throw BadRequestResponse("mode must be CRAWL, ACTIVE, or AUDIT")
        }
        val id = UUID.randomUUID().toString()
        tasks[id] = ManagedTask(id, mode, task)
        ctx.status(202).json(ScanResponse(id, mode, task.statusMessage()))
    }

    @OpenApi(
        path = "/scanner/tasks/{id}",
        methods = [HttpMethod.GET],
        summary = "Read scan task status",
        tags = ["Scanner"],
        pathParams = [OpenApiParam(name = "id", type = String::class, description = "OmniAPI scan task ID", required = true)],
        responses = [
            OpenApiResponse(status = "200", content = [OpenApiContent(from = ScanTaskDto::class)]),
            OpenApiResponse(status = "404", content = [OpenApiContent(from = dev.omniapi.model.ErrorResponse::class)])
        ],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun task(ctx: Context) {
        val managed = tasks[ctx.pathParam("id")] ?: throw NotFoundResponse("Unknown scanner task")
        val task = managed.task
        ctx.json(
            ScanTaskDto(
                taskId = managed.id,
                mode = managed.mode,
                status = task.statusMessage(),
                requestCount = task.requestCount(),
                errorCount = task.errorCount(),
                insertionPointCount = (task as? Audit)?.insertionPointCount()
            )
        )
    }

    @OpenApi(
        path = "/scanner/issues",
        methods = [HttpMethod.GET],
        summary = "Read Scanner issues",
        tags = ["Scanner"],
        queryParams = [
            OpenApiParam(name = "offset", type = Int::class, description = "Zero-based result offset"),
            OpenApiParam(name = "limit", type = Int::class, description = "Page size from 1 to 500"),
            OpenApiParam(name = "severity", type = String::class, description = "Optional severity name"),
            OpenApiParam(name = "urlPrefix", type = String::class, description = "Native site-map URL prefix filter")
        ],
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = IssuePage::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun issues(ctx: Context) = gate.run {
        val (offset, limit) = Paging.parameters(ctx)
        val severity = ctx.queryParam("severity")?.uppercase()
        val urlPrefix = ctx.queryParam("urlPrefix")?.let(Validation::httpUrl)
        val allIssues = if (urlPrefix == null) api.siteMap().issues()
        else api.siteMap().issues(SiteMapFilter.prefixFilter(urlPrefix))
        val snapshot = if (severity == null) allIssues else allIssues.filter { it.severity().name == severity }
        val page = Paging.page(snapshot, offset, limit)
        ctx.json(IssuePage(page.items.map { issue ->
            IssueDto(
                name = issue.name(),
                detail = issue.detail(),
                remediation = issue.remediation(),
                baseUrl = issue.baseUrl(),
                severity = issue.severity().name,
                confidence = issue.confidence().name,
                evidence = issue.requestResponses().map(MontoyaMapper::httpMessage)
            )
        }, page.offset, page.limit, page.returned, page.total))
    }

    fun close() {
        // Stopping the REST listener must not cancel or delete scans that Burp is still running.
        tasks.clear()
    }

    companion object { private const val MAX_TASKS = 256 }
}
