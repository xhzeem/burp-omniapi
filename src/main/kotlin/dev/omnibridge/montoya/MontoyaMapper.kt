package dev.omnibridge.montoya

import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import dev.omnibridge.model.HttpMessageDto
import dev.omnibridge.model.SitemapItemDto
import dev.omnibridge.util.BinaryCodec
import java.net.URI

object MontoyaMapper {
    fun request(service: HttpService, bytes: ByteArray): HttpRequest =
        HttpRequest.httpRequest(service, burp.api.montoya.core.ByteArray.byteArray(*bytes))

    fun request(host: String, port: Int, secure: Boolean, bytes: ByteArray): HttpRequest =
        request(HttpService.httpService(host, port, secure), bytes)

    fun httpMessage(item: HttpRequestResponse, timestamp: String? = null): HttpMessageDto {
        val request = item.request()
        val service = item.httpService()
        val hasResponse = item.hasResponse()
        val response = if (hasResponse) item.response() else null
        return HttpMessageDto(
            requestBase64 = BinaryCodec.encode(request.toByteArray().bytes),
            responseBase64 = response?.let { BinaryCodec.encode(it.toByteArray().bytes) },
            url = request.url(),
            method = request.method(),
            host = service.host(),
            port = service.port(),
            secure = service.secure(),
            timestamp = timestamp,
            statusCode = response?.statusCode()?.toInt(),
            mimeType = response?.mimeType()?.toString()
        )
    }

    fun proxyMessage(item: ProxyHttpRequestResponse): HttpMessageDto {
        val request = item.finalRequest()
        val service = item.httpService()
        val response = if (item.hasResponse()) item.response() else null
        return HttpMessageDto(
            requestBase64 = BinaryCodec.encode(request.toByteArray().bytes),
            responseBase64 = response?.let { BinaryCodec.encode(it.toByteArray().bytes) },
            url = request.url(),
            method = request.method(),
            host = service.host(),
            port = service.port(),
            secure = service.secure(),
            timestamp = item.time()?.toString(),
            statusCode = response?.statusCode()?.toInt(),
            mimeType = response?.mimeType()?.toString()
        )
    }

    fun sitemapItem(item: HttpRequestResponse): SitemapItemDto {
        val request = item.request()
        val service = item.httpService()
        val response = if (item.hasResponse()) item.response() else null
        val uri = runCatching { URI(request.url()) }.getOrNull()
        val pathSegments = uri?.path.orEmpty().split('/').filter(String::isNotBlank)
        val parentPath = pathSegments.dropLast(1).joinToString("/", prefix = "/")
        val parent = if (pathSegments.isEmpty() || uri == null) null else URI(
            uri.scheme, uri.userInfo, uri.host, uri.port, parentPath, null, null
        ).toASCIIString()
        return SitemapItemDto(
            url = request.url(),
            parentUrl = parent,
            pathSegments = pathSegments,
            method = request.method(),
            host = service.host(),
            port = service.port(),
            secure = service.secure(),
            statusCode = response?.statusCode()?.toInt(),
            requestBase64 = BinaryCodec.encode(request.toByteArray().bytes),
            responseBase64 = response?.let { BinaryCodec.encode(it.toByteArray().bytes) }
        )
    }

    private val burp.api.montoya.core.ByteArray.bytes: ByteArray
        get() = getBytes()
}
