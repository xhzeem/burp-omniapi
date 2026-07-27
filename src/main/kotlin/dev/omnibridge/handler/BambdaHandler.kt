package dev.omnibridge.handler

import burp.api.montoya.MontoyaApi
import dev.omnibridge.model.BambdaImportRequest
import dev.omnibridge.model.BambdaImportResponse
import dev.omnibridge.server.bodyValidated
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BambdaHandler(private val api: MontoyaApi) {
    private val importLock = ReentrantLock()

    @OpenApi(
        path = "/api/v1/bambda/import",
        methods = [HttpMethod.POST],
        summary = "Import a Bambda",
        tags = ["Bambda"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = BambdaImportRequest::class)]),
        responses = [
            OpenApiResponse(status = "201", content = [OpenApiContent(from = BambdaImportResponse::class)]),
            OpenApiResponse(status = "422", content = [OpenApiContent(from = BambdaImportResponse::class)])
        ],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun importBambda(ctx: Context) {
        val input = ctx.bodyValidated<BambdaImportRequest>()
        if (input.source.isBlank()) throw BadRequestResponse("source must not be blank")
        if (input.source.length > 1_000_000) throw BadRequestResponse("source exceeds 1,000,000 characters")
        val result = importLock.withLock { api.bambda().importBambda(input.source) }
        ctx.status(if (result.importErrors().isEmpty()) 201 else 422)
            .json(BambdaImportResponse(result.status().name, result.importErrors()))
    }
}
