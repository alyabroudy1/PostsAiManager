package com.postsaimanager.core.ai.catalog.gguf

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Tests for [GgufReader].
 *
 * Imported models bypass the signed catalog, so there is no publisher hash to verify them
 * against. This header check is the only thing standing between a user-picked file and a
 * buffer handed to native code.
 */
class GgufReaderTest {

    private fun header(
        magic: ByteArray = byteArrayOf(0x47, 0x47, 0x55, 0x46),
        version: Int = 3,
        tensorCount: Long = 291,
        metadataCount: Long = 24,
    ): ByteArray {
        val out = ByteArray(24)
        magic.copyInto(out, 0, 0, minOf(4, magic.size))
        // uint32 LE
        for (i in 0 until 4) out[4 + i] = ((version shr (8 * i)) and 0xFF).toByte()
        // uint64 LE
        for (i in 0 until 8) out[8 + i] = ((tensorCount shr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 8) out[16 + i] = ((metadataCount shr (8 * i)) and 0xFF).toByte()
        return out
    }

    private fun validate(bytes: ByteArray) = GgufReader.validate(ByteArrayInputStream(bytes))

    @Test
    fun `accepts a well-formed v3 header`() {
        val result = validate(header())

        assertThat(result).isInstanceOf(GgufValidation.Valid::class.java)
        val h = (result as GgufValidation.Valid).header
        assertThat(h.version).isEqualTo(3)
        assertThat(h.tensorCount).isEqualTo(291)
        assertThat(h.metadataCount).isEqualTo(24)
    }

    @Test
    fun `accepts v2`() {
        assertThat(validate(header(version = 2))).isInstanceOf(GgufValidation.Valid::class.java)
    }

    @Test
    @DisplayName("rejects a file that is not GGUF at all")
    fun `rejects wrong magic`() {
        // A PNG, say — the kind of thing a file picker makes easy to choose by mistake.
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(20)
        assertThat(validate(png)).isEqualTo(GgufValidation.NotGguf)
    }

    @Test
    fun `rejects an unsupported format version`() {
        assertThat(validate(header(version = 1)))
            .isEqualTo(GgufValidation.UnsupportedVersion(1))
        assertThat(validate(header(version = 99)))
            .isEqualTo(GgufValidation.UnsupportedVersion(99))
    }

    @Test
    fun `rejects a truncated file`() {
        assertThat(validate(ByteArray(10))).isEqualTo(GgufValidation.Truncated)
        assertThat(validate(ByteArray(0))).isEqualTo(GgufValidation.Truncated)
    }

    @Test
    @DisplayName("rejects a header declaring no tensors")
    fun `rejects zero tensors`() {
        val result = validate(header(tensorCount = 0))
        assertThat(result).isInstanceOf(GgufValidation.Implausible::class.java)
    }

    @Test
    @DisplayName("rejects absurd counts rather than letting native code allocate against them")
    fun `rejects out-of-range counts`() {
        assertThat(validate(header(tensorCount = Long.MAX_VALUE / 2)))
            .isInstanceOf(GgufValidation.Implausible::class.java)
        assertThat(validate(header(metadataCount = 5_000_000)))
            .isInstanceOf(GgufValidation.Implausible::class.java)
    }

    @Test
    fun `reads only the header, never the whole file`() {
        // 24 header bytes followed by "the rest of a 2 GB model".
        val stream = ByteArrayInputStream(header() + ByteArray(1_000))

        GgufReader.validate(stream)

        // 1000 bytes still unread — the reader did not drain the stream.
        assertThat(stream.available()).isEqualTo(1_000)
    }
}
