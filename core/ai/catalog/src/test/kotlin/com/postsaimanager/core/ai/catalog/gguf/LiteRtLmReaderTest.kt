package com.postsaimanager.core.ai.catalog.gguf

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Tests for [LiteRtLmReader], the `.litertlm` counterpart of [GgufReader]: the header check is the only gate between a
 * user-picked file and the native engine, since an imported file has no publisher hash.
 *
 * [realHeader] is the first 20 bytes of Google's published `gemma-4-E2B-it.litertlm`: `LITERTLM`, then major 1, minor 5, patch 0.
 */
class LiteRtLmReaderTest {

    private val realHeader = byteArrayOf(
        0x4C, 0x49, 0x54, 0x45, 0x52, 0x54, 0x4C, 0x4D,
        0x01, 0x00, 0x00, 0x00,
        0x05, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
    )

    private fun validate(bytes: ByteArray) = LiteRtLmReader.validate(ByteArrayInputStream(bytes))

    @Test
    @DisplayName("accepts the header of the published Gemma 4 file")
    fun `accepts a real header`() {
        val result = validate(realHeader + ByteArray(1000))

        assertThat(result).isEqualTo(LiteRtLmValidation.Valid(LiteRtLmHeader(major = 1, minor = 5, patch = 0)))
    }

    @Test
    @DisplayName("a GGUF file is not a LiteRT-LM file")
    fun `rejects a gguf file`() {
        val gguf = byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x03, 0, 0, 0) + ByteArray(40)

        assertThat(validate(gguf)).isEqualTo(LiteRtLmValidation.NotLiteRtLm)
    }

    @Test
    fun `rejects a file with the wrong magic`() {
        val wrong = realHeader.copyOf().also { it[0] = 0x00 }

        assertThat(validate(wrong)).isEqualTo(LiteRtLmValidation.NotLiteRtLm)
    }

    @Test
    @DisplayName("a file that starts like one but ends inside the header is truncated, not foreign")
    fun `reports a truncated header`() {
        assertThat(validate(realHeader.copyOf(12))).isEqualTo(LiteRtLmValidation.Truncated)
    }

    @Test
    fun `an empty file is not a LiteRT-LM file`() {
        assertThat(validate(ByteArray(0))).isEqualTo(LiteRtLmValidation.NotLiteRtLm)
    }

    @Test
    @DisplayName("another major version of the format is refused with its version")
    fun `rejects an unsupported major version`() {
        val v2 = realHeader.copyOf().also { it[8] = 0x02 }

        assertThat(validate(v2)).isEqualTo(LiteRtLmValidation.UnsupportedVersion(LiteRtLmHeader(2, 5, 0)))
    }

    @Test
    @DisplayName("reads only the header, so a multi-gigabyte file is not read to be rejected")
    fun `consumes only the header`() {
        val stream = ByteArrayInputStream(realHeader + ByteArray(5000))

        LiteRtLmReader.validate(stream)

        assertThat(stream.available()).isEqualTo(5000)
    }
}
