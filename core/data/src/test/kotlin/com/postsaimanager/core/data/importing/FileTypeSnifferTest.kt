package com.postsaimanager.core.data.importing

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.importing.ImportedKind
import org.junit.jupiter.api.Test

/** The real type comes from the first bytes, never from a name or a claimed MIME type. */
class FileTypeSnifferTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun `an invented PDF resource is a PDF`() {
        val header = javaClass.getResourceAsStream("/import/tiny-invented.pdf")!!.use { it.readNBytes(FileTypeSniffer.HEADER_BYTES) }
        assertThat(FileTypeSniffer.sniff(header)).isEqualTo(ImportedKind.PDF)
    }

    @Test
    fun `JPEG, PNG and WebP headers are images`() {
        assertThat(FileTypeSniffer.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10))).isEqualTo(ImportedKind.IMAGE)
        assertThat(FileTypeSniffer.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0))).isEqualTo(ImportedKind.IMAGE)
        assertThat(FileTypeSniffer.sniff(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WEBP") + ascii("VP8 "))).isEqualTo(ImportedKind.IMAGE)
    }

    @Test
    fun `HEIC, HEIF and AVIF containers are images`() {
        for (brand in listOf("heic", "heix", "mif1", "msf1", "avif")) {
            assertThat(FileTypeSniffer.sniff(bytes(0, 0, 0, 0x18) + ascii("ftyp") + ascii(brand))).isEqualTo(ImportedKind.IMAGE)
        }
    }

    @Test
    fun `other containers are refused`() {
        // an MP4 video has an ftyp box too, with a video brand
        assertThat(FileTypeSniffer.sniff(bytes(0, 0, 0, 0x18) + ascii("ftyp") + ascii("isom"))).isNull()
        // a RIFF file that is not WebP (a WAV)
        assertThat(FileTypeSniffer.sniff(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WAVE"))).isNull()
    }

    @Test
    fun `a name cannot make a file what it is not`() {
        // plain text claiming nothing: no header, no kind
        assertThat(FileTypeSniffer.sniff(ascii("Dear Sir, this is not a PDF.pdf"))).isNull()
        // a ZIP, a script, an HTML page
        assertThat(FileTypeSniffer.sniff(bytes(0x50, 0x4B, 0x03, 0x04, 0, 0))).isNull()
        assertThat(FileTypeSniffer.sniff(ascii("<html><body>"))).isNull()
    }

    @Test
    fun `empty and too short input is refused`() {
        assertThat(FileTypeSniffer.sniff(ByteArray(0))).isNull()
        assertThat(FileTypeSniffer.sniff(bytes(0xFF, 0xD8))).isNull()
        assertThat(FileTypeSniffer.sniff(ascii("%PDF"))).isNull()
    }
}
