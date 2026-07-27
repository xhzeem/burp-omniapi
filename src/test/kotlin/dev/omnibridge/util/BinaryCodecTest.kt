package dev.omnibridge.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class BinaryCodecTest {
    @Test
    fun `round trips arbitrary binary data`() {
        val data = Random(31337).nextBytes(65_537).also {
            it[0] = 0
            it[1] = -1
        }
        assertContentEquals(data, BinaryCodec.decode(BinaryCodec.encode(data)))
    }

    @Test
    fun `rejects malformed base64`() {
        assertFailsWith<IllegalArgumentException> { BinaryCodec.decode("not base64!") }
        assertFailsWith<IllegalArgumentException> { BinaryCodec.decode("") }
    }

    @Test
    fun `enforces decoded size limit`() {
        val encoded = BinaryCodec.encode(ByteArray(17))
        assertFailsWith<IllegalArgumentException> { BinaryCodec.decode(encoded, maxBytes = 16) }
    }
}
