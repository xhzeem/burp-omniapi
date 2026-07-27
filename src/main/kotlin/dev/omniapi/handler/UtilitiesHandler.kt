package dev.omniapi.handler

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ByteArray
import burp.api.montoya.utilities.CompressionType
import burp.api.montoya.utilities.DigestAlgorithm
import burp.api.montoya.utilities.HtmlEncoding
import burp.api.montoya.utilities.URLEncoding
import dev.omniapi.model.UtilityRequest
import dev.omniapi.model.UtilityResponse
import dev.omniapi.server.OperationGate
import dev.omniapi.server.bodyValidated
import dev.omniapi.util.BinaryCodec
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*

class UtilitiesHandler(private val api: MontoyaApi, private val gate: OperationGate) {
    @OpenApi(
        path = "/api/v1/utilities/transform",
        methods = [HttpMethod.POST],
        summary = "Run a safe Montoya utility transform",
        tags = ["Utilities"],
        requestBody = OpenApiRequestBody(content = [OpenApiContent(from = UtilityRequest::class)]),
        responses = [
            OpenApiResponse(status = "200", content = [OpenApiContent(from = UtilityResponse::class)]),
            OpenApiResponse(status = "400", content = [OpenApiContent(from = dev.omniapi.model.ErrorResponse::class)])
        ],
        security = [OpenApiSecurity(name = "ApiKeyAuth"), OpenApiSecurity(name = "ApiKeyQueryAuth")]
    )
    fun transform(ctx: Context) = gate.run {
        val request = ctx.bodyValidated<UtilityRequest>()
        val utilities = api.utilities()
        val input = request.input
        val response = try {
            when (request.operation.uppercase()) {
                "BASE64_ENCODE" -> UtilityResponse(
                    output = utilities.base64Utils().encodeToString(required(input, "input"))
                )
                "BASE64_DECODE" -> UtilityResponse(
                    dataBase64 = BinaryCodec.encode(utilities.base64Utils().decode(required(input, "input")).getBytes())
                )
                "URL_ENCODE" -> UtilityResponse(
                    output = if (request.option == null) utilities.urlUtils().encode(required(input, "input"))
                    else utilities.urlUtils().encode(required(input, "input"), enumValue<URLEncoding>(request.option))
                )
                "URL_DECODE" -> UtilityResponse(output = utilities.urlUtils().decode(required(input, "input")))
                "HTML_ENCODE" -> UtilityResponse(
                    output = if (request.option == null) utilities.htmlUtils().encode(required(input, "input"))
                    else utilities.htmlUtils().encode(required(input, "input"), enumValue<HtmlEncoding>(request.option))
                )
                "HTML_DECODE" -> UtilityResponse(output = utilities.htmlUtils().decode(required(input, "input")))
                "COMPRESS" -> {
                    val bytes = bytes(request)
                    val output = utilities.compressionUtils().compress(bytes, enumValue(required(request.option, "option")))
                    UtilityResponse(dataBase64 = BinaryCodec.encode(output.getBytes()))
                }
                "DECOMPRESS" -> {
                    val bytes = bytes(request)
                    val output = utilities.compressionUtils().decompress(bytes, enumValue(required(request.option, "option")))
                    UtilityResponse(dataBase64 = BinaryCodec.encode(output.getBytes()))
                }
                "DIGEST" -> {
                    val output = utilities.cryptoUtils().generateDigest(
                        bytes(request), enumValue<DigestAlgorithm>(required(request.option, "option"))
                    )
                    UtilityResponse(dataBase64 = BinaryCodec.encode(output.getBytes()))
                }
                "ASCII_TO_HEX" -> UtilityResponse(output = utilities.stringUtils().convertAsciiToHexString(required(input, "input")))
                "HEX_TO_ASCII" -> UtilityResponse(output = utilities.stringUtils().convertHexStringToAscii(required(input, "input")))
                "BINARY_TO_OCTAL" -> UtilityResponse(output = utilities.numberUtils().convertBinaryToOctal(required(input, "input")))
                "BINARY_TO_DECIMAL" -> UtilityResponse(output = utilities.numberUtils().convertBinaryToDecimal(required(input, "input")))
                "BINARY_TO_HEX" -> UtilityResponse(output = utilities.numberUtils().convertBinaryToHex(required(input, "input")))
                "OCTAL_TO_BINARY" -> UtilityResponse(output = utilities.numberUtils().convertOctalToBinary(required(input, "input")))
                "OCTAL_TO_DECIMAL" -> UtilityResponse(output = utilities.numberUtils().convertOctalToDecimal(required(input, "input")))
                "OCTAL_TO_HEX" -> UtilityResponse(output = utilities.numberUtils().convertOctalToHex(required(input, "input")))
                "DECIMAL_TO_BINARY" -> UtilityResponse(output = utilities.numberUtils().convertDecimalToBinary(required(input, "input")))
                "DECIMAL_TO_OCTAL" -> UtilityResponse(output = utilities.numberUtils().convertDecimalToOctal(required(input, "input")))
                "DECIMAL_TO_HEX" -> UtilityResponse(output = utilities.numberUtils().convertDecimalToHex(required(input, "input")))
                "HEX_TO_BINARY" -> UtilityResponse(output = utilities.numberUtils().convertHexToBinary(required(input, "input")))
                "HEX_TO_OCTAL" -> UtilityResponse(output = utilities.numberUtils().convertHexToOctal(required(input, "input")))
                "HEX_TO_DECIMAL" -> UtilityResponse(output = utilities.numberUtils().convertHexToDecimal(required(input, "input")))
                "JSON_READ" -> UtilityResponse(
                    output = utilities.jsonUtils().read(required(input, "input"), required(request.path, "path"))
                )
                "JSON_ADD" -> UtilityResponse(
                    output = utilities.jsonUtils().add(required(input, "input"), required(request.path, "path"), required(request.value, "value"))
                )
                "JSON_UPDATE" -> UtilityResponse(
                    output = utilities.jsonUtils().update(required(input, "input"), required(request.path, "path"), required(request.value, "value"))
                )
                "JSON_REMOVE" -> UtilityResponse(
                    output = utilities.jsonUtils().remove(required(input, "input"), required(request.path, "path"))
                )
                "JSON_VALIDATE" -> UtilityResponse(boolean = utilities.jsonUtils().isValidJson(required(input, "input")))
                "RANDOM" -> {
                    val random = utilities.randomUtils()
                    val output = when {
                        request.minLength != null || request.maxLength != null -> {
                            val min = request.minLength ?: throw BadRequestResponse("minLength is required")
                            val max = request.maxLength ?: throw BadRequestResponse("maxLength is required")
                            validateLength(min); validateLength(max)
                            if (min > max) throw BadRequestResponse("minLength must not exceed maxLength")
                            if (request.alphabet == null) random.randomString(min, max)
                            else random.randomString(min, max, request.alphabet)
                        }
                        else -> {
                            val length = request.length ?: throw BadRequestResponse("length is required")
                            validateLength(length)
                            if (request.alphabet == null) random.randomString(length)
                            else random.randomString(length, request.alphabet)
                        }
                    }
                    UtilityResponse(output = output)
                }
                else -> throw BadRequestResponse("Unknown utility operation")
            }
        } catch (e: IllegalArgumentException) {
            throw BadRequestResponse(e.message ?: "Invalid utility option")
        }
        ctx.json(response)
    }

    private fun bytes(request: UtilityRequest): ByteArray =
        ByteArray.byteArray(*BinaryCodec.decode(required(request.dataBase64, "dataBase64")))

    private fun required(value: String?, name: String): String =
        value?.takeIf { it.isNotEmpty() } ?: throw BadRequestResponse("$name is required")

    private fun validateLength(value: Int) {
        if (value !in 1..100_000) throw BadRequestResponse("length must be between 1 and 100000")
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T = try {
        enumValueOf<T>(value.uppercase())
    } catch (_: IllegalArgumentException) {
        throw BadRequestResponse("Invalid option '$value' for ${T::class.simpleName}")
    }
}
