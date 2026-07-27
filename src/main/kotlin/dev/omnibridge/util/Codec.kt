package dev.omnibridge.util

import java.util.Base64

object BinaryCodec {
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(value: String, maxBytes: Int = 16 * 1024 * 1024): ByteArray {
        require(value.isNotBlank()) { "Base64 value must not be blank" }
        require(value.length <= ((maxBytes.toLong() * 4 / 3) + 8)) { "Base64 input is too large" }
        val decoded = try {
            decoder.decode(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid RFC 4648 base64 data")
        }
        require(decoded.size <= maxBytes) { "Decoded data exceeds $maxBytes bytes" }
        return decoded
    }
}
