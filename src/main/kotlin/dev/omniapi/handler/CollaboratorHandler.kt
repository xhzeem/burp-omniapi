package dev.omniapi.handler

import burp.api.montoya.collaborator.CollaboratorClient
import burp.api.montoya.collaborator.InteractionFilter
import burp.api.montoya.collaborator.PayloadOption
import dev.omniapi.model.CollaboratorPayloadRequest
import dev.omniapi.model.CollaboratorPayloadResponse
import dev.omniapi.model.DnsInteractionDto
import dev.omniapi.model.HttpInteractionDto
import dev.omniapi.model.InteractionDto
import dev.omniapi.model.InteractionPage
import dev.omniapi.model.SmtpInteractionDto
import dev.omniapi.montoya.MontoyaMapper
import dev.omniapi.server.OperationGate
import dev.omniapi.server.bodyValidated
import dev.omniapi.util.BinaryCodec
import dev.omniapi.util.Paging
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*

class CollaboratorHandler(
    private val client: CollaboratorClient,
    private val gate: OperationGate
) {

    @OpenApi(
        path = "/api/v1/collaborator/payload",
        methods = [HttpMethod.POST],
        summary = "Generate a Collaborator payload",
        tags = ["Collaborator"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = CollaboratorPayloadRequest::class)], required = false),
        responses = [OpenApiResponse(status = "201", content = [OpenApiContent(from = CollaboratorPayloadResponse::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun payload(ctx: Context) {
        val input = if (ctx.body().isBlank()) CollaboratorPayloadRequest() else ctx.bodyValidated()
        val options = if (input.withoutServerLocation) arrayOf(PayloadOption.WITHOUT_SERVER_LOCATION) else emptyArray()
        val payload = if (input.customData == null) {
            client.generatePayload(*options)
        } else {
            if (input.customData.length !in 1..64) throw BadRequestResponse("customData must be 1 to 64 characters")
            client.generatePayload(input.customData, *options)
        }
        ctx.status(201).json(
            CollaboratorPayloadResponse(
                payload = payload.toString(),
                interactionId = payload.id().toString(),
                customData = payload.customData().orElse(null)
            )
        )
    }

    @OpenApi(
        path = "/api/v1/collaborator/interactions",
        methods = [HttpMethod.GET],
        summary = "Poll Collaborator interactions",
        tags = ["Collaborator"],
        queryParams = [
            OpenApiParam(name = "offset", type = Int::class, description = "Zero-based result offset"),
            OpenApiParam(name = "limit", type = Int::class, description = "Page size from 1 to 500"),
            OpenApiParam(name = "interactionId", type = String::class, description = "Filter by interaction ID"),
            OpenApiParam(name = "payload", type = String::class, description = "Filter by generated payload")
        ],
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = InteractionPage::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun interactions(ctx: Context) = gate.run {
        val (offset, limit) = Paging.parameters(ctx)
        val interactionId = ctx.queryParam("interactionId")
        val payload = ctx.queryParam("payload")
        if (interactionId != null && payload != null) {
            throw BadRequestResponse("Specify interactionId or payload, not both")
        }
        val snapshot = when {
            interactionId != null -> client.getInteractions(InteractionFilter.interactionIdFilter(interactionId))
            payload != null -> client.getInteractions(InteractionFilter.interactionPayloadFilter(payload))
            else -> client.allInteractions
        }.toList()
        val page = Paging.page(snapshot, offset, limit)
        ctx.json(InteractionPage(page.items.map { interaction ->
            InteractionDto(
                id = interaction.id().toString(),
                type = interaction.type().name,
                timestamp = interaction.timeStamp().toString(),
                clientIp = interaction.clientIp().hostAddress,
                clientPort = interaction.clientPort(),
                customData = interaction.customData().orElse(null),
                dns = interaction.dnsDetails().map {
                    DnsInteractionDto(it.queryType().name, BinaryCodec.encode(it.query().bytes))
                }.orElse(null),
                http = interaction.httpDetails().map {
                    HttpInteractionDto(it.protocol().name, MontoyaMapper.httpMessage(it.requestResponse()))
                }.orElse(null),
                smtp = interaction.smtpDetails().map {
                    SmtpInteractionDto(it.protocol().name, it.conversation())
                }.orElse(null)
            )
        }, page.offset, page.limit, page.returned, page.total))
    }

    private val burp.api.montoya.core.ByteArray.bytes: ByteArray
        get() = getBytes()
}
