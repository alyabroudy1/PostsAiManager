package com.postsaimanager.core.ai.catalog.gguf

import java.io.InputStream

/** The parts of a GGUF header worth validating before accepting a file. */
data class GgufHeader(
    val version: Int,
    val tensorCount: Long,
    val metadataCount: Long,
)

sealed interface GgufValidation {
    data class Valid(val header: GgufHeader) : GgufValidation

    /** Not a GGUF file at all — wrong magic bytes. */
    data object NotGguf : GgufValidation

    /** GGUF, but a format revision this build does not understand. */
    data class UnsupportedVersion(val version: Int) : GgufValidation

    /** Truncated before the header could be read. */
    data object Truncated : GgufValidation

    /** Structurally GGUF but self-evidently wrong. */
    data class Implausible(val reason: String) : GgufValidation
}

/**
 * Validates that a user-supplied file really is a GGUF model before it is copied in.
 *
 * Imported models bypass the signed catalog entirely — there is no publisher hash to check
 * them against — so this header check is the only gate between "the user picked a file" and
 * "the app hands a buffer to native code". Rejecting early gives a clear error instead of a
 * crash inside llama.cpp on a 2 GB file that was never a model.
 *
 * GGUF layout (little-endian):
 * ```
 * magic          4 bytes  "GGUF"
 * version        uint32
 * tensor_count   uint64
 * metadata_count uint64
 * ```
 */
object GgufReader {

    /** ASCII "GGUF". */
    private val MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

    /** Format revisions this build accepts. v1 predates current tooling. */
    private val SUPPORTED_VERSIONS = setOf(2, 3)

    private const val HEADER_BYTES = 24

    /**
     * Reads and validates the header. Consumes only the first [HEADER_BYTES] bytes, so it
     * is safe to call on a stream over a multi-gigabyte file.
     */
    fun validate(input: InputStream): GgufValidation {
        val header = ByteArray(HEADER_BYTES)
        var read = 0
        while (read < HEADER_BYTES) {
            val n = input.read(header, read, HEADER_BYTES - read)
            if (n <= 0) return GgufValidation.Truncated
            read += n
        }

        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) return GgufValidation.NotGguf
        }

        val version = readU32(header, 4).toInt()
        if (version !in SUPPORTED_VERSIONS) return GgufValidation.UnsupportedVersion(version)

        val tensorCount = readU64(header, 8)
        val metadataCount = readU64(header, 16)

        // A model with no tensors is not a model. Absurd counts mean the file is corrupt or
        // the header was misread — either way, refuse rather than let native code allocate
        // against them.
        if (tensorCount <= 0) {
            return GgufValidation.Implausible("declares no tensors")
        }
        if (tensorCount > MAX_PLAUSIBLE_TENSORS || metadataCount > MAX_PLAUSIBLE_METADATA) {
            return GgufValidation.Implausible("header counts are out of range")
        }

        return GgufValidation.Valid(GgufHeader(version, tensorCount, metadataCount))
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private const val MAX_PLAUSIBLE_TENSORS = 100_000L
    private const val MAX_PLAUSIBLE_METADATA = 100_000L
}
