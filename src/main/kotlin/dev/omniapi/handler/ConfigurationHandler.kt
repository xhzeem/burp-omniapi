package dev.omniapi.handler

import burp.api.montoya.MontoyaApi
import dev.omniapi.model.ConfigurationJsonRequest
import dev.omniapi.model.ConfigurationJsonResponse
import dev.omniapi.model.ConfigurationUpdateResponse
import dev.omniapi.server.bodyValidated
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.HttpMethod
import io.javalin.openapi.OpenApi
import io.javalin.openapi.OpenApiContent
import io.javalin.openapi.OpenApiRequestBody
import io.javalin.openapi.OpenApiResponse
import io.javalin.openapi.OpenApiSecurity

class ConfigurationHandler(private val api: MontoyaApi) {
    @OpenApi(
        path = "/api/v1/config/project",
        methods = [HttpMethod.GET],
        summary = "Export Burp project options",
        tags = ["Configuration"],
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = ConfigurationJsonResponse::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun project(ctx: Context) {
        ctx.json(ConfigurationJsonResponse(api.burpSuite().exportProjectOptionsAsJson()))
    }

    @OpenApi(
        path = "/api/v1/config/project",
        methods = [HttpMethod.PUT],
        summary = "Merge Burp project options",
        description = "WARNING: configuration changes can execute code.",
        tags = ["Configuration"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = ConfigurationJsonRequest::class)]),
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = ConfigurationUpdateResponse::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun setProject(ctx: Context) {
        val input = validated(ctx)
        api.burpSuite().importProjectOptionsFromJson(input.json)
        ctx.json(ConfigurationUpdateResponse("PROJECT_OPTIONS_APPLIED"))
    }

    @OpenApi(
        path = "/api/v1/config/user",
        methods = [HttpMethod.GET],
        summary = "Export Burp user options",
        tags = ["Configuration"],
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = ConfigurationJsonResponse::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun user(ctx: Context) {
        ctx.json(ConfigurationJsonResponse(api.burpSuite().exportUserOptionsAsJson()))
    }

    @OpenApi(
        path = "/api/v1/config/user",
        methods = [HttpMethod.PUT],
        summary = "Merge Burp user options",
        description = "WARNING: configuration changes can execute code.",
        tags = ["Configuration"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = ConfigurationJsonRequest::class)]),
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = ConfigurationUpdateResponse::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun setUser(ctx: Context) {
        val input = validated(ctx)
        api.burpSuite().importUserOptionsFromJson(input.json)
        ctx.json(ConfigurationUpdateResponse("USER_OPTIONS_APPLIED"))
    }

    private fun validated(ctx: Context): ConfigurationJsonRequest =
        ctx.bodyValidated<ConfigurationJsonRequest>().also {
            if (it.json.isBlank()) throw BadRequestResponse("json must not be blank")
            if (it.json.length > MAX_OPTIONS_SIZE) throw BadRequestResponse("json exceeds 16 MiB")
        }

    companion object {
        private const val MAX_OPTIONS_SIZE = 16 * 1024 * 1024
    }
}
