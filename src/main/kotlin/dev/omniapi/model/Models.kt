package dev.omniapi.model

data class ErrorResponse(
    val error: String,
    val message: String,
    val requestId: String? = null,
    val details: Map<String, Any?>? = null
)

data class HealthResponse(val status: String, val product: String, val version: String)
data class StatusResponse(val status: String, val detail: String? = null)
data class Page<T>(val items: List<T>, val offset: Int, val limit: Int, val returned: Int, val total: Int)
data class HttpMessagePage(val items: List<HttpMessageDto>, val offset: Int, val limit: Int, val returned: Int, val total: Int)
data class SitemapPage(val items: List<SitemapItemDto>, val offset: Int, val limit: Int, val returned: Int, val total: Int)
data class IssuePage(val items: List<IssueDto>, val offset: Int, val limit: Int, val returned: Int, val total: Int)
data class InteractionPage(val items: List<InteractionDto>, val offset: Int, val limit: Int, val returned: Int, val total: Int)
data class OrganizerPage(val items: List<OrganizerItemDto>, val offset: Int, val limit: Int, val returned: Int, val total: Int)
data class WebSocketEventPage(val items: List<WebSocketEventDto>, val offset: Int, val limit: Int, val returned: Int, val total: Int)

data class HttpMessageDto(
    val requestBase64: String,
    val responseBase64: String?,
    val url: String,
    val method: String,
    val host: String,
    val port: Int,
    val secure: Boolean,
    val timestamp: String? = null,
    val statusCode: Int? = null,
    val mimeType: String? = null
)

data class InterceptCommand(
    val enabled: Boolean? = null,
    val messageId: String? = null,
    val action: String? = null
)
data class PendingInterceptDto(val id: String, val requestBase64: String, val url: String)
data class InterceptStatus(
    val enabled: Boolean,
    val pendingMessages: List<PendingInterceptDto> = emptyList(),
    val limitation: String? = null
)

data class SitemapItemDto(
    val url: String,
    val parentUrl: String?,
    val pathSegments: List<String>,
    val method: String,
    val host: String,
    val port: Int,
    val secure: Boolean,
    val statusCode: Int?,
    val requestBase64: String,
    val responseBase64: String?
)

data class ScopeRequest(val target: String = "", val action: String = "")
data class ScopeResponse(val target: String, val inScope: Boolean, val normalizedTargets: List<String>)

data class RepeaterRequest(
    val host: String = "",
    val port: Int = 0,
    val secure: Boolean = false,
    val requestBase64: String = "",
    val tabName: String? = null,
    val existingTabId: String? = null
)

data class ScanRequest(
    val mode: String = "",
    val urls: List<String> = emptyList(),
    val configuration: String = "LEGACY_ACTIVE_AUDIT_CHECKS"
)
data class ScanResponse(val taskId: String, val mode: String, val status: String)
data class ScanTaskDto(
    val taskId: String,
    val mode: String,
    val status: String,
    val requestCount: Int,
    val errorCount: Int,
    val insertionPointCount: Int? = null
)
data class IssueDto(
    val name: String,
    val detail: String?,
    val remediation: String?,
    val baseUrl: String,
    val severity: String,
    val confidence: String,
    val evidence: List<HttpMessageDto>
)

data class InsertionPoint(val start: Int = 0, val endExclusive: Int = 0)
data class IntruderRequest(
    val host: String = "",
    val port: Int = 0,
    val secure: Boolean = false,
    val requestBase64: String = "",
    val insertionPoints: List<InsertionPoint> = emptyList(),
    val tabName: String? = null,
    val payloads: List<String> = emptyList(),
    val attackType: String? = null
)
data class IntruderResponse(val status: String, val limitation: String? = null)

data class CollaboratorPayloadRequest(val customData: String? = null, val withoutServerLocation: Boolean = false)
data class CollaboratorPayloadResponse(val payload: String, val interactionId: String, val customData: String?)
data class InteractionDto(
    val id: String,
    val type: String,
    val timestamp: String,
    val clientIp: String,
    val clientPort: Int,
    val customData: String?,
    val dns: DnsInteractionDto? = null,
    val http: HttpInteractionDto? = null,
    val smtp: SmtpInteractionDto? = null
)
data class DnsInteractionDto(val queryType: String, val queryBase64: String)
data class HttpInteractionDto(val protocol: String, val message: HttpMessageDto)
data class SmtpInteractionDto(val protocol: String, val conversation: String)

data class HttpSendRequest(
    val host: String = "",
    val port: Int = 0,
    val secure: Boolean = false,
    val requestBase64: String = "",
    val mode: String = "AUTO"
)
data class CookieDto(val name: String, val value: String, val domain: String, val path: String, val expiresAt: String?)
data class CookieSetRequest(
    val name: String = "",
    val value: String = "",
    val domain: String = "",
    val path: String = "/",
    val expiresAt: String? = null
)

data class WebSocketConnectRequest(
    val host: String = "",
    val port: Int = 0,
    val secure: Boolean = false,
    val upgradeRequestBase64: String = ""
)
data class WebSocketConnectResponse(val sessionId: String?, val status: String, val upgradeResponseBase64: String?)
data class WebSocketSendRequest(val type: String = "TEXT", val text: String? = null, val dataBase64: String? = null)
data class WebSocketEventDto(
    val sequence: Long,
    val type: String,
    val direction: String?,
    val timestamp: String,
    val text: String? = null,
    val dataBase64: String? = null
)

data class ToolSendRequest(
    val dataBase64: String = "",
    val secondDataBase64: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val secure: Boolean? = null,
    val responseBase64: String? = null
)
data class OrganizerItemDto(val id: Int, val status: String, val message: HttpMessageDto)
data class BambdaImportRequest(val source: String = "")
data class BambdaImportResponse(val status: String, val errors: List<String>)
data class ConfigurationJsonRequest(val json: String = "")
data class ConfigurationJsonResponse(val json: String)
data class ConfigurationUpdateResponse(val status: String)

data class UtilityRequest(
    val operation: String = "",
    val input: String? = null,
    val dataBase64: String? = null,
    val option: String? = null,
    val path: String? = null,
    val value: String? = null,
    val length: Int? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val alphabet: String? = null
)
data class UtilityResponse(val output: String? = null, val dataBase64: String? = null, val boolean: Boolean? = null)

data class SystemInfoDto(
    val product: String,
    val extensionVersion: String,
    val burpVersion: String,
    val projectName: String,
    val projectId: String,
    val bindAddress: String,
    val port: Int,
    val serverStatus: String,
    val modules: Map<String, Boolean>
)
data class CapabilityDto(val operation: String, val supported: Boolean, val detail: String)
data class CapabilitiesDto(val modules: Map<String, Boolean>, val capabilities: List<CapabilityDto>)
