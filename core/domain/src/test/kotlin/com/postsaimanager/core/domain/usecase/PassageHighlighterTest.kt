package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

class PassageHighlighterTest {

    private fun block(text: String, top: Float) =
        OcrBlock(text, TextBounds(0.1f, top, 0.9f, top + 0.05f), 0.9f)

    private val due = block("Die Zahlung ist fällig am 15.03.2026.", 0.2f)
    private val amount = block("Nachzahlung: 1.234,50 Euro", 0.3f)
    private val unrelated = block("Mit freundlichen Grüßen Ihr Finanzamt", 0.8f)

    @Test
    fun `a block whose words are in the chunk is highlighted`() {
        val result = PassageHighlighter.highlight(
            "Bitte beachten Sie: Die Zahlung ist fällig am 15.03.2026, sonst drohen Säumniszuschläge.",
            listOf(due, unrelated),
        )
        assertThat(result).containsExactly(due.bounds)
    }

    @Test
    fun `case and punctuation do not matter`() {
        val result = PassageHighlighter.highlight("DIE ZAHLUNG IST FÄLLIG AM 15 03 2026", listOf(due))
        assertThat(result).containsExactly(due.bounds)
    }

    @Test
    fun `no overlap gives no highlight`() {
        val result = PassageHighlighter.highlight("Kindergeld wurde bewilligt", listOf(due, amount, unrelated))
        assertThat(result).isEmpty()
    }

    @Test
    fun `a single shared common word is not enough`() {
        val result = PassageHighlighter.highlight("Die Rechnung liegt bei", listOf(due))
        assertThat(result).isEmpty()
    }

    @Test
    fun `a passage spanning several blocks highlights each of them`() {
        val result = PassageHighlighter.highlight(
            "Die Zahlung ist fällig am 15.03.2026. Nachzahlung: 1.234,50 Euro",
            listOf(due, amount, unrelated),
        )
        assertThat(result).containsExactly(due.bounds, amount.bounds).inOrder()
    }

    @Test
    fun `a chunk cut from the middle of one long block still matches it`() {
        val long = block(
            "Einleitung zum Bescheid und allgemeine Hinweise. Die Zahlung ist fällig am 15.03.2026. " +
                "Weitere Hinweise zur Rechtsbehelfsbelehrung folgen auf der nächsten Seite dieses Schreibens.",
            0.4f,
        )
        val result = PassageHighlighter.highlight("Die Zahlung ist fällig am 15.03.2026", listOf(long))
        assertThat(result).containsExactly(long.bounds)
    }

    @Test
    fun `empty inputs are safe`() {
        assertThat(PassageHighlighter.highlight("", listOf(due))).isEmpty()
        assertThat(PassageHighlighter.highlight("text", emptyList())).isEmpty()
    }
}
