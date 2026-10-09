package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.database.entity.DocumentPageEntity
import com.postsaimanager.core.data.mapper.DocumentMapper
import org.junit.jupiter.api.Test

/** A page whose text the user corrected is read from that text, with no word boxes needed and no recognition to replace it. */
class StoredOcrUserTextTest {

    private val mapper = DocumentMapper()

    private fun page(number: Int, text: String?, source: String, blocks: String? = null) = DocumentPageEntity(
        id = "p$number", documentId = "d1", pageNumber = number, imagePath = "file:///$number.jpg", processedPath = null, ocrText = text,
        ocrConfidence = 0.8f, ocrBlocks = blocks, width = 100, height = 200, textSource = source,
    )

    @Test
    fun `a corrected page is reused with plain blocks even when it has no stored blocks or word boxes`() {
        val pages = listOf(page(1, "Rechnung\nBitte zahlen", "USER"))

        val reused = StoredOcr.reuse(pages, mapper, requireWordBoxes = true)

        val result = reused!!.single().second!!
        assertThat(result.fullText).isEqualTo("Rechnung\nBitte zahlen")
        assertThat(result.blocks.flatMap { it.lines }.map { it.text }).containsExactly("Rechnung", "Bitte zahlen").inOrder()
    }

    @Test
    fun `an uncorrected page without blocks still makes the document be read again as a whole`() {
        val pages = listOf(page(1, "Rechnung", "USER"), page(2, "Seite zwei", "OCR"))

        assertThat(StoredOcr.reuse(pages, mapper, requireWordBoxes = true)).isNull()
    }

    @Test
    fun `the page's text source is read from the entity`() {
        assertThat(StoredOcr.isUserText(page(1, "x", "USER"))).isTrue()
        assertThat(StoredOcr.isUserText(page(1, "x", "OCR"))).isFalse()
        assertThat(mapper.pageToDomain(page(1, "x", "USER")).textSource.name).isEqualTo("USER")
        assertThat(mapper.pageToEntity(mapper.pageToDomain(page(1, "x", "USER"))).textSource).isEqualTo("USER")
    }
}
