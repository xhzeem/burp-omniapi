package dev.omniapi.handler

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.HttpMode
import dev.omniapi.model.CookieDto
import dev.omniapi.model.CookieSetRequest
import dev.omniapi.model.HttpSendRequest
import dev.omniapi.montoya.MontoyaMapper
import dev.omniapi.server.OperationGate
import dev.omniapi.server.bodyValidated
import dev.omniapi.util.BinaryCodec
import dev.omniapi.util.Validation
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class HttpHandler(private val api: MontoyaApi, private val gate: OperationGate) {
    private val cookieLock = ReentrantLock()

    @OpenApi(
        path = "/http/send",
        methods = [HttpMethod.POST],
        summary = "Send an HTTP request",
        tags = ["HTTP"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = HttpSendRequest::class)]),
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = dev.omniapi.model.HttpMessageDto::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun send(ctx: Context) = gate.run {
        val input = ctx.bodyValidated<HttpSendRequest>()
        val mode = try { HttpMode.valueOf(input.mode.uppercase()) } catch (_: IllegalArgumentException) {
            throw BadRequestResponse("mode must be AUTO, HTTP_1, HTTP_2, or HTTP_2_IGNORE_ALPN")
        }
        val request = MontoyaMapper.request(
            Validation.nonBlank(input.host, "host", 253),
            Validation.port(input.port),
            input.secure,
            BinaryCodec.decode(input.requestBase64)
        )
        ctx.json(MontoyaMapper.httpMessage(api.http().sendRequest(request, mode)))
    }

    @OpenApi(
        path = "/http/cookies",
        methods = [HttpMethod.GET],
        summary = "Read Burp's cookie jar",
        tags = ["HTTP"],
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = Array<CookieDto>::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun cookies(ctx: Context) {
        val cookies = api.http().cookieJar().cookies().map {
            CookieDto(it.name(), it.value(), it.domain(), it.path(), it.expiration().map(Any::toString).orElse(null))
        }
        ctx.json(cookies)
    }

    @OpenApi(
        path = "/http/cookies",
        methods = [HttpMethod.PUT],
        summary = "Set a cookie in Burp's cookie jar",
        tags = ["HTTP"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = CookieSetRequest::class)]),
        responses = [OpenApiResponse(status = "204")],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun setCookie(ctx: Context) {
        val input = ctx.bodyValidated<CookieSetRequest>()
        val expiration = input.expiresAt?.let {
            try { ZonedDateTime.parse(it) } catch (_: DateTimeParseException) {
                throw BadRequestResponse("expiresAt must be an ISO-8601 zoned timestamp")
            }
        }
        cookieLock.withLock {
            api.http().cookieJar().setCookie(
                Validation.nonBlank(input.name, "name", 256),
                input.value,
                Validation.nonBlank(input.domain, "domain", 253),
                Validation.nonBlank(input.path, "path", 2048),
                expiration
            )
        }
        ctx.status(204)
    }
}
