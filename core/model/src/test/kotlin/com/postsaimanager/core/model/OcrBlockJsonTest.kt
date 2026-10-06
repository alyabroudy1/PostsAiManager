package com.postsaimanager.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** `document_pages.ocrBlocks` is stored JSON: blocks written before lines existed must still decode, and new ones must round-trip. */
class OcrBlockJsonTest {

    // The same configuration DocumentMapper decodes with.
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(OcrBlock.serializer())

    @Test
    fun `json stored before lines existed decodes with no lines`() {
        val old = """[{"text":"Bescheid","bounds":{"left":0.1,"top":0.2,"right":0.9,"bottom":0.3},"confidence":0.9,"language":"de"}]"""

        val blocks = json.decodeFromString(serializer, old)

        assertThat(blocks).hasSize(1)
        assertThat(blocks[0].text).isEqualTo("Bescheid")
        assertThat(blocks[0].lines).isEmpty()
    }

    @Test
    fun `a block with lines round-trips`() {
        val block = OcrBlock(
            text = "Zeile eins\nZeile zwei",
            bounds = TextBounds(0.1f, 0.2f, 0.9f, 0.3f),
            confidence = 0.8f,
            language = "de",
            lines = listOf(
                OcrLine("Zeile eins", TextBounds(0.1f, 0.2f, 0.9f, 0.25f)),
                OcrLine("Zeile zwei", TextBounds(0.1f, 0.25f, 0.9f, 0.3f)),
            ),
        )

        val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, listOf(block)))

        assertThat(decoded).containsExactly(block)
    }

    @Test
    fun `a block without lines is written exactly as before`() {
        val block = OcrBlock("x", TextBounds(0f, 0f, 1f, 1f), 0.5f)

        assertThat(json.encodeToString(serializer, listOf(block))).doesNotContain("lines")
    }
}
