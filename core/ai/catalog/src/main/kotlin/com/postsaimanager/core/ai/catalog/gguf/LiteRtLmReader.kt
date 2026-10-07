package com.postsaimanager.core.ai.catalog.gguf

import java.io.InputStream

/** The version a `.litertlm` header declares. */
data class LiteRtLmHeader(val major: Int, val minor: Int, val patch: Int)

sealed interface LiteRtLmValidation {
    data class Valid(val header: LiteRtLmHeader) : LiteRtLmValidation

    /** Not a LiteRT-LM file at all: the file does not start with the format's magic. */
    data object NotLiteRtLm : LiteRtLmValidation

    /** A LiteRT-LM file of a format generation this build does not read. */
    data class UnsupportedVersion(val header: LiteRtLmHeader) : LiteRtLmValidation

    /** Truncated before the header could be read. */
    data object Truncated : LiteRtLmValidation
}

/**
 * Validates that a user-supplied file really is a LiteRT-LM model (`.litertlm`) before it is copied in, the counterpart of
 * [GgufReader].
 *
 * As with an imported GGUF there is no publisher hash to check it against, so the header is the gate between "the user picked a
 * file" and "the app hands it to a native engine". The magic is read from the files Google publishes (the Gallery's Gemma 4
 * builds).
 *
 * LiteRT-LM layout (little-endian):
 * ```
 * magic    8 bytes  "LITERTLM"
 * major    uint32
 * minor    uint32
 * patch    uint32
 * ```
 * The engine decides what it can run; this only refuses what is not the format, or a generation of it (a different major
 * version) this build was not made for.
 */
object LiteRtLmReader {

    /** ASCII "LITERTLM". */
    private val MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)

    /** Format generations this build accepts. */
    private val SUPPORTED_MAJOR_VERSIONS = setOf(1)

    private const val HEADER_BYTES = 20

    /** Reads and validates the header. Consumes only the first [HEADER_BYTES] bytes, so it is safe on a multi-gigabyte stream. */
    fun validate(input: InputStream): LiteRtLmValidation {
        val header = ByteArray(HEADER_BYTES)
        var read = 0
        while (read < HEADER_BYTES) {
            val n = input.read(header, read, HEADER_BYTES - read)
            if (n <= 0) return if (startsWithMagic(header, read)) LiteRtLmValidation.Truncated else LiteRtLmValidation.NotLiteRtLm
            read += n
        }
        if (!startsWithMagic(header, HEADER_BYTES)) return LiteRtLmValidation.NotLiteRtLm

        val parsed = LiteRtLmHeader(major = readU32(header, 8), minor = readU32(header, 12), patch = readU32(header, 16))
        if (parsed.major !in SUPPORTED_MAJOR_VERSIONS) return LiteRtLmValidation.UnsupportedVersion(parsed)
        return LiteRtLmValidation.Valid(parsed)
    }

    /** Whether the first [available] bytes of [bytes] are consistent with the magic (so a short file that starts right is Truncated). */
    private fun startsWithMagic(bytes: ByteArray, available: Int): Boolean {
        val n = minOf(MAGIC.size, available)
        if (n == 0) return false
        for (i in 0 until n) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
