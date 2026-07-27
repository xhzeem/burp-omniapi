package dev.omniapi.handler

import burp.api.montoya.core.ByteArray
import burp.api.montoya.core.Range
import burp.api.montoya.http.HttpService
import burp.api.montoya.intruder.HttpRequestTemplate
import burp.api.montoya.MontoyaApi
import dev.omniapi.model.IntruderRequest
import dev.omniapi.model.IntruderResponse
import dev.omniapi.server.CapabilityUnavailable
import dev.omniapi.server.bodyValidated
import dev.omniapi.util.BinaryCodec
import dev.omniapi.util.Validation
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import javax.swing.SwingUtilities

class IntruderHandler(private val api: MontoyaApi) {
    @OpenApi(
        path = "/api/v1/intruder/attack",
        methods = [HttpMethod.POST],
        summary = "Open an Intruder request template",
        tags = ["Intruder"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = IntruderRequest::class)]),
        responses = [
            OpenApiResponse(status = "202", content = [OpenApiContent(from = IntruderResponse::class)]),
            OpenApiResponse(status = "400", content = [OpenApiContent(from = dev.omniapi.model.ErrorResponse::class)]),
            OpenApiResponse(status = "501", content = [OpenApiContent(from = dev.omniapi.model.ErrorResponse::class)])
        ],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun attack(ctx: Context) {
        val input = ctx.bodyValidated<IntruderRequest>()
        if (input.payloads.isNotEmpty() || !input.attackType.isNullOrBlank()) {
            throw CapabilityUnavailable(
                "Montoya can configure an Intruder tab but cannot set attack type/payload lists or launch the attack"
            )
        }
        val bytes = BinaryCodec.decode(input.requestBase64)
        val ranges = input.insertionPoints.map {
            if (it.start < 0 || it.endExclusive <= it.start || it.endExclusive > bytes.size) {
                throw BadRequestResponse("Every insertion point must be within the request and have start < endExclusive")
            }
            Range.range(it.start, it.endExclusive)
        }.sortedBy { it.startIndexInclusive() }
        ranges.zipWithNext().forEach { (a, b) ->
            if (a.endIndexExclusive() > b.startIndexInclusive()) {
                throw BadRequestResponse("Insertion points must not overlap")
            }
        }
        val service = HttpService.httpService(
            Validation.nonBlank(input.host, "host", 253),
            Validation.port(input.port),
            input.secure
        )
        val template = HttpRequestTemplate.httpRequestTemplate(ByteArray.byteArray(*bytes), ranges)
        SwingUtilities.invokeLater {
            if (input.tabName.isNullOrBlank()) api.intruder().sendToIntruder(service, template)
            else api.intruder().sendToIntruder(service, template, input.tabName.take(200))
        }
        ctx.status(202).json(
            IntruderResponse(
                status = "OPENED_IN_INTRUDER",
                limitation = "Montoya does not expose attack launch or payload configuration"
            )
        )
    }
}
