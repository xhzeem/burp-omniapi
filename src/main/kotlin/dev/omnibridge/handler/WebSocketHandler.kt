package dev.omnibridge.handler

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ByteArray
import burp.api.montoya.core.Registration
import burp.api.montoya.websocket.BinaryMessage
import burp.api.montoya.websocket.TextMessage
import burp.api.montoya.websocket.extension.ExtensionWebSocket
import burp.api.montoya.websocket.extension.ExtensionWebSocketMessageHandler
import dev.omnibridge.model.StatusResponse
import dev.omnibridge.model.WebSocketConnectRequest
import dev.omnibridge.model.WebSocketConnectResponse
import dev.omnibridge.model.WebSocketEventDto
import dev.omnibridge.model.WebSocketEventPage
import dev.omnibridge.model.WebSocketSendRequest
import dev.omnibridge.montoya.MontoyaMapper
import dev.omnibridge.server.OperationGate
import dev.omnibridge.server.bodyValidated
import dev.omnibridge.util.BinaryCodec
import dev.omnibridge.util.Paging
import dev.omnibridge.util.Validation
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import io.javalin.openapi.*
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class WebSocketHandler(private val api: MontoyaApi, private val gate: OperationGate) {
    private class Session(val socket: ExtensionWebSocket) {
        val sequence = AtomicLong()
        val events = ArrayDeque<WebSocketEventDto>()
        lateinit var registration: Registration

        fun append(event: WebSocketEventDto) = synchronized(events) {
            while (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(event)
        }

        fun snapshot(afterSequence: Long): List<WebSocketEventDto> = synchronized(events) {
            events.filter { it.sequence > afterSequence }
        }

        companion object { const val MAX_EVENTS = 1_000 }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    @OpenApi(
        path = "/api/v1/websockets",
        methods = [HttpMethod.POST],
        summary = "Create an extension WebSocket",
        tags = ["WebSockets"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = WebSocketConnectRequest::class)]),
        responses = [
            OpenApiResponse(status = "201", content = [OpenApiContent(from = WebSocketConnectResponse::class)]),
            OpenApiResponse(status = "422", content = [OpenApiContent(from = WebSocketConnectResponse::class)])
        ],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun connect(ctx: Context) = gate.run {
        if (sessions.size >= MAX_SESSIONS) {
            throw dev.omnibridge.server.TooBusy("The WebSocket session registry is full")
        }
        val input = ctx.bodyValidated<WebSocketConnectRequest>()
        val request = MontoyaMapper.request(
            Validation.nonBlank(input.host, "host", 253),
            Validation.port(input.port),
            input.secure,
            BinaryCodec.decode(input.upgradeRequestBase64)
        )
        val creation = api.websockets().createWebSocket(request)
        val upgradeResponse = creation.upgradeResponse().map {
            BinaryCodec.encode(it.toByteArray().getBytes())
        }.orElse(null)
        val socket = creation.webSocket().orElse(null)
        if (socket == null) {
            ctx.status(422).json(WebSocketConnectResponse(null, creation.status().name, upgradeResponse))
            return@run
        }
        val id = UUID.randomUUID().toString()
        val session = Session(socket)
        session.registration = socket.registerMessageHandler(object : ExtensionWebSocketMessageHandler {
            override fun textMessageReceived(message: TextMessage) {
                session.append(
                    WebSocketEventDto(
                        sequence = session.sequence.incrementAndGet(),
                        type = "TEXT",
                        direction = message.direction().name,
                        timestamp = Instant.now().toString(),
                        text = message.payload()
                    )
                )
            }

            override fun binaryMessageReceived(message: BinaryMessage) {
                session.append(
                    WebSocketEventDto(
                        sequence = session.sequence.incrementAndGet(),
                        type = "BINARY",
                        direction = message.direction().name,
                        timestamp = Instant.now().toString(),
                        dataBase64 = BinaryCodec.encode(message.payload().getBytes())
                    )
                )
            }

            override fun onClose() {
                session.append(
                    WebSocketEventDto(
                        sequence = session.sequence.incrementAndGet(),
                        type = "CLOSED",
                        direction = null,
                        timestamp = Instant.now().toString()
                    )
                )
            }
        })
        sessions[id] = session
        ctx.status(201).json(WebSocketConnectResponse(id, creation.status().name, upgradeResponse))
    }

    @OpenApi(
        path = "/api/v1/websockets/{id}/messages",
        methods = [HttpMethod.POST],
        summary = "Send a WebSocket message",
        tags = ["WebSockets"],
        pathParams = [OpenApiParam(name = "id", type = String::class, description = "WebSocket session ID", required = true)],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = WebSocketSendRequest::class)]),
        responses = [OpenApiResponse(status = "202", content = [OpenApiContent(from = StatusResponse::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun send(ctx: Context) {
        val session = session(ctx)
        val input = ctx.bodyValidated<WebSocketSendRequest>()
        when (input.type.uppercase()) {
            "TEXT" -> session.socket.sendTextMessage(input.text ?: throw BadRequestResponse("text is required"))
            "BINARY" -> session.socket.sendBinaryMessage(
                ByteArray.byteArray(*BinaryCodec.decode(input.dataBase64 ?: throw BadRequestResponse("dataBase64 is required")))
            )
            else -> throw BadRequestResponse("type must be TEXT or BINARY")
        }
        ctx.status(202).json(StatusResponse("SENT"))
    }

    @OpenApi(
        path = "/api/v1/websockets/{id}/events",
        methods = [HttpMethod.GET],
        summary = "Poll WebSocket events",
        tags = ["WebSockets"],
        pathParams = [OpenApiParam(name = "id", type = String::class, description = "WebSocket session ID", required = true)],
        queryParams = [
            OpenApiParam(name = "offset", type = Int::class, description = "Zero-based result offset"),
            OpenApiParam(name = "limit", type = Int::class, description = "Page size from 1 to 500"),
            OpenApiParam(name = "afterSequence", type = Long::class, description = "Only events after this sequence")
        ],
        responses = [OpenApiResponse(status = "200", content = [OpenApiContent(from = WebSocketEventPage::class)])],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun events(ctx: Context) {
        val session = session(ctx)
        val (offset, limit) = Paging.parameters(ctx)
        val after = ctx.queryParam("afterSequence")?.let {
            it.toLongOrNull() ?: throw BadRequestResponse("afterSequence must be an integer")
        } ?: 0
        if (after < 0) throw BadRequestResponse("afterSequence must be non-negative")
        val page = Paging.page(session.snapshot(after), offset, limit)
        ctx.json(WebSocketEventPage(page.items, page.offset, page.limit, page.returned, page.total))
    }

    @OpenApi(
        path = "/api/v1/websockets/{id}",
        methods = [HttpMethod.DELETE],
        summary = "Close a WebSocket",
        tags = ["WebSockets"],
        pathParams = [OpenApiParam(name = "id", type = String::class, description = "WebSocket session ID", required = true)],
        responses = [OpenApiResponse(status = "204")],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun close(ctx: Context) {
        val id = ctx.pathParam("id")
        val session = sessions.remove(id) ?: throw NotFoundResponse("Unknown WebSocket session")
        closeSession(session)
        ctx.status(204)
    }

    fun closeAll() {
        sessions.values.forEach(::closeSession)
        sessions.clear()
    }

    private fun session(ctx: Context): Session =
        sessions[ctx.pathParam("id")] ?: throw NotFoundResponse("Unknown WebSocket session")

    private fun closeSession(session: Session) {
        runCatching { session.registration.deregister() }
        runCatching { session.socket.close() }
    }

    companion object { private const val MAX_SESSIONS = 128 }
}
