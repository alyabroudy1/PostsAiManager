package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The one labelled answer of the "Questions" reader, split by label, and the shapes of its items. */
class QuestionAnswerParserTest {

    @Test
    @DisplayName("every label's line is its answer; none and a missing label read as no answer")
    fun `labels`() {
        val a = QuestionAnswerParser.parse(
            """
            SENDER: Jobcenter Musterstadt | authority
            RECIPIENT: Maria Mustermann | person
            CONTACT: none
            TITLE: Eingangsbestätigung Antrag
            """.trimIndent(),
        )

        assertThat(a[QaLabel.SENDER]).isEqualTo("Jobcenter Musterstadt | authority")
        assertThat(a[QaLabel.RECIPIENT]).isEqualTo("Maria Mustermann | person")
        assertThat(a[QaLabel.CONTACT]).isNull()
        assertThat(a[QaLabel.DATES]).isNull()
        assertThat(a.answered).containsExactly(QaLabel.SENDER, QaLabel.RECIPIENT, QaLabel.CONTACT, QaLabel.TITLE)
    }

    @Test
    @DisplayName("markdown around a label, any case and a list wrapped onto the next lines are understood")
    fun `tolerant of the model's habits`() {
        val a = QuestionAnswerParser.parse(
            "**SENDER:** Stadtwerke Beispiel\n- dates: 01.09.2026 — DUE_DATE;\n  15.09.2026 — DEADLINE\n* Amounts: 12,50 EUR — FEE\nCommentary no label",
        )

        assertThat(a[QaLabel.SENDER]).isEqualTo("Stadtwerke Beispiel")
        assertThat(QaText.items(a[QaLabel.DATES])).containsExactly("01.09.2026 — DUE_DATE", "15.09.2026 — DEADLINE").inOrder()
        assertThat(a[QaLabel.AMOUNTS]).contains("12,50 EUR — FEE")
    }

    @Test
    @DisplayName("an item is split into its parts by a dash, an em dash or a bar; an empty part keeps its place")
    fun `parts`() {
        assertThat(QaText.parts("Nadine Beispiel | 0123 456-701 | none")).containsExactly("Nadine Beispiel", "0123 456-701", "").inOrder()
        assertThat(QaText.parts("2026-09-01 — DUE_DATE")).containsExactly("2026-09-01", "DUE_DATE").inOrder()
        assertThat(QaText.parts("yes - pay - 30.11.2026")).containsExactly("yes", "pay", "30.11.2026").inOrder()
    }

    @Test
    @DisplayName("a list word is its leading word without separators or case")
    fun `words`() {
        assertThat(QaText.key("DUE_DATE (the date by which)")).isEqualTo("duedate")
        assertThat(QaText.key("\"Pay\"")).isEqualTo("pay")
        assertThat(QaText.isNone("None.")).isTrue()
        assertThat(QaText.isNone("n")).isFalse()
    }
}
