package dev.omniapi.handler

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ByteArray
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.responses.HttpResponse
import dev.omniapi.model.OrganizerItemDto
import dev.omniapi.model.OrganizerPage
import dev.omniapi.model.StatusResponse
import dev.omniapi.model.ToolSendRequest
import dev.omniapi.montoya.MontoyaMapper
import dev.omniapi.server.OperationGate
import dev.omniapi.server.bodyValidated
import dev.omniapi.util.BinaryCodec
import dev.omniapi.util.Paging
import dev.omniapi.util.Validation
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import javax.swing.SwingUtilities

class ToolsHandler(private val api: MontoyaApi, private val gate: OperationGate) {
    @OpenApi(
        path = "/api/v1/tools/decoder", methods = [HttpMethod.POST], summary = "Send bytes to Decoder", tags = ["Tools"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = ToolSendRequest::class)]),
        responses = [OpenApiResponse(status = "202", content = [OpenApiContent(from = StatusResponse::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun decoder(ctx: Context) {
        val input = ctx.bodyValidated<ToolSendRequest>()
        val data = ByteArray.byteArray(*BinaryCodec.decode(input.dataBase64))
        SwingUtilities.invokeLater { api.decoder().sendToDecoder(data) }
        ctx.status(202).json(StatusResponse("SENT_TO_DECODER"))
    }

    @OpenApi(
        path = "/api/v1/tools/comparer", methods = [HttpMethod.POST], summary = "Send bytes to Comparer", tags = ["Tools"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = ToolSendRequest::class)]),
        responses = [OpenApiResponse(status = "202", content = [OpenApiContent(from = StatusResponse::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun comparer(ctx: Context) {
        val input = ctx.bodyValidated<ToolSendRequest>()
        val values = listOfNotNull(input.dataBase64, input.secondDataBase64)
            .map { ByteArray.byteArray(*BinaryCodec.decode(it)) }
        if (values.isEmpty()) throw BadRequestResponse("At least one data value is required")
        SwingUtilities.invokeLater { api.comparer().sendToComparer(*values.toTypedArray()) }
        ctx.status(202).json(StatusResponse("SENT_TO_COMPARER"))
    }

    @OpenApi(
        path = "/api/v1/tools/organizer", methods = [HttpMethod.POST], summary = "Send an HTTP message to Organizer", tags = ["Tools"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = ToolSendRequest::class)]),
        responses = [OpenApiResponse(status = "202", content = [OpenApiContent(from = StatusResponse::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun organizer(ctx: Context) {
        val input = ctx.bodyValidated<ToolSendRequest>()
        val host = Validation.nonBlank(input.host ?: "", "host", 253)
        val port = Validation.port(input.port ?: 0)
        val request = MontoyaMapper.request(host, port, input.secure ?: false, BinaryCodec.decode(input.dataBase64))
        val requestResponse = input.responseBase64?.let {
            val response = HttpResponse.httpResponse(ByteArray.byteArray(*BinaryCodec.decode(it)))
            HttpRequestResponse.httpRequestResponse(request, response)
        }
        SwingUtilities.invokeLater {
            if (requestResponse == null) {
                api.organizer().sendToOrganizer(request)
            } else {
                api.organizer().sendToOrganizer(requestResponse)
            }
        }
        ctx.status(202).json(StatusResponse("SENT_TO_ORGANIZER"))
    }

    @OpenApi(
        path = "/api/v1/tools/organizer", methods = [HttpMethod.GET], summary = "Read Organizer items", tags = ["Tools"],
        queryParams = [
            OpenApiParam(name = "offset", type = Int::class, description = "Zero-based result offset"),
            OpenApiParam(name = "limit", type = Int::class, description = "Page size from 1 to 500")
        ],
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = OrganizerPage::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun organizerItems(ctx: Context) = gate.run {
        val (offset, limit) = Paging.parameters(ctx)
        val snapshot = api.organizer().items().toList()
        val page = Paging.page(snapshot, offset, limit)
        ctx.json(OrganizerPage(page.items.map {
            OrganizerItemDto(it.id(), it.status().name, MontoyaMapper.httpMessage(it))
        }, page.offset, page.limit, page.returned, page.total))
    }
}
