package dev.omniapi.handler

import burp.api.montoya.MontoyaApi
import dev.omniapi.model.CapabilitiesDto
import dev.omniapi.model.CapabilityDto
import dev.omniapi.model.HealthResponse
import dev.omniapi.model.SystemInfoDto
import dev.omniapi.state.ApiState
import io.javalin.http.Context
import io.javalin.openapi.*

class SystemHandler(private val api: MontoyaApi, private val state: ApiState) {
    @OpenApi(
        path = "/health",
        methods = [HttpMethod.GET],
        summary = "Liveness check",
        tags = ["System"],
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = HealthResponse::class)])]
    )
    fun health(ctx: Context) {
        ctx.json(HealthResponse("ok", PRODUCT, VERSION))
    }

    @OpenApi(
        path = "/api/v1/system/info",
        methods = [HttpMethod.GET],
        summary = "Read Burp OmniBridge status",
        tags = ["System"],
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = SystemInfoDto::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun info(ctx: Context) {
        ctx.json(
            SystemInfoDto(
                product = PRODUCT,
                extensionVersion = VERSION,
                burpVersion = api.burpSuite().version().toString(),
                projectName = api.project().name(),
                projectId = api.project().id(),
                bindAddress = state.bindAddress.get(),
                port = state.port.get(),
                serverStatus = state.serverStatus.get().name,
                modules = state.moduleSnapshot()
            )
        )
    }

    @OpenApi(
        path = "/api/v1/system/capabilities",
        methods = [HttpMethod.GET],
        summary = "Discover supported capabilities",
        tags = ["System"],
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = CapabilitiesDto::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun capabilities(ctx: Context) {
        ctx.json(
            CapabilitiesDto(
                modules = state.moduleSnapshot(),
                capabilities = listOf(
                    CapabilityDto("proxy.history", true, "Paginated history with URL and host regex filters"),
                    CapabilityDto("proxy.intercept.toggle", true, "Master interception can be enabled and disabled"),
                    CapabilityDto("proxy.intercept.queue", false, "Montoya does not expose pending intercepted messages"),
                    CapabilityDto("proxy.intercept.forwardDrop", false, "Montoya does not expose retroactive queue actions"),
                    CapabilityDto("repeater.createTab", true, "Creates a new named or default tab"),
                    CapabilityDto("repeater.targetExistingTab", false, "Montoya does not identify existing tabs"),
                    CapabilityDto("scanner.crawl", true, "Starts a crawl from seed URLs"),
                    CapabilityDto("scanner.activeAudit", true, "Starts an audit and adds URL requests"),
                    CapabilityDto("scanner.forcePassive", false, "Montoya cannot invoke its passive scanner"),
                    CapabilityDto("intruder.configureTemplate", true, "Opens a request template with insertion points"),
                    CapabilityDto("intruder.launchAttack", false, "Montoya cannot configure payload lists or start attacks"),
                    CapabilityDto("shell.execute", false, "Explicitly excluded from OmniBridge"),
                    CapabilityDto("burp.shutdown", false, "Explicitly excluded from OmniBridge"),
                    CapabilityDto("ai.prompt", false, "Explicitly excluded from OmniBridge")
                )
            )
        )
    }

    companion object {
        const val PRODUCT = "Burp OmniBridge"
        const val VERSION = "0.2.0"
    }
}
