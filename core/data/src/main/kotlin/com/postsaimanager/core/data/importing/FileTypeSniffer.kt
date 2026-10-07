package com.postsaimanager.core.data.importing

import com.postsaimanager.core.domain.importing.ImportedKind

/**
 * What a file really is, read from its first bytes. A name or a claimed MIME type is what the sender says; the header is what
 * the file is. Only the formats the platform decodes are recognised: PDF, JPEG, PNG, WebP, HEIC/HEIF and AVIF.
 */
object FileTypeSniffer {

    /** How many leading bytes [sniff] needs. */
    const val HEADER_BYTES: Int = 32

    private val heifBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif", "avis")

    /** The kind of a file starting with [header], or null when it is neither a PDF nor a supported image. */
    fun sniff(header: ByteArray): ImportedKind? = when {
        startsWith(header, "%PDF-".toByteArray(Charsets.US_ASCII)) -> ImportedKind.PDF
        startsWith(header, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> ImportedKind.IMAGE
        startsWith(header, byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)) ->
            ImportedKind.IMAGE
        isWebp(header) -> ImportedKind.IMAGE
        isHeif(header) -> ImportedKind.IMAGE
        else -> null
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    /** `RIFF` size `WEBP`. */
    private fun isWebp(h: ByteArray): Boolean =
        h.size >= 12 && ascii(h, 0, 4) == "RIFF" && ascii(h, 8, 4) == "WEBP"

    /** An ISO base media file: size, `ftyp`, then a brand that is an image brand. */
    private fun isHeif(h: ByteArray): Boolean =
        h.size >= 12 && ascii(h, 4, 4) == "ftyp" && ascii(h, 8, 4) in heifBrands

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)
}
