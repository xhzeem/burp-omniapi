package dev.omnibridge.handler

import burp.api.montoya.MontoyaApi
import dev.omnibridge.model.RepeaterRequest
import dev.omnibridge.model.StatusResponse
import dev.omnibridge.montoya.MontoyaMapper
import dev.omnibridge.server.CapabilityUnavailable
import dev.omnibridge.server.bodyValidated
import dev.omnibridge.util.BinaryCodec
import dev.omnibridge.util.Validation
import io.javalin.http.Context
import io.javalin.openapi.*
import javax.swing.SwingUtilities

class RepeaterHandler(private val api: MontoyaApi) {
    @OpenApi(
        path = "/api/v1/repeater/send",
        methods = [HttpMethod.POST],
        summary = "Send a request to Repeater",
        tags = ["Repeater"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = RepeaterRequest::class)]),
        responses = [
            OpenApiResponse(status = "202", content = [OpenApiContent(from = StatusResponse::class)]),
            OpenApiResponse(status = "501", content = [OpenApiContent(from = dev.omnibridge.model.ErrorResponse::class)])
        ],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun send(ctx: Context) {
        val input = ctx.bodyValidated<RepeaterRequest>()
        if (input.existingTabId != null) {
            throw CapabilityUnavailable("Montoya can create Repeater tabs but cannot target an existing tab")
        }
        val request = MontoyaMapper.request(
            Validation.nonBlank(input.host, "host", 253),
            Validation.port(input.port),
            input.secure,
            BinaryCodec.decode(input.requestBase64)
        )
        SwingUtilities.invokeLater {
            if (input.tabName.isNullOrBlank()) api.repeater().sendToRepeater(request)
            else api.repeater().sendToRepeater(request, input.tabName.take(200))
        }
        ctx.status(202).json(StatusResponse("OPENED_IN_REPEATER"))
    }
}
