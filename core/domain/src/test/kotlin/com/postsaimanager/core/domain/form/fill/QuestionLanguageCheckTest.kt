package com.postsaimanager.core.domain.form.fill

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

class QuestionLanguageCheckTest {

    private fun block(text: String) = OcrBlock(text, TextBounds(0f, 0f, 1f, 0.02f), 0.9f, null)

    private val german = QuestionLanguageCheck.vocabularyOf(
        listOf(
            listOf(
                block("Angaben zum Kind"), block("Vorname"), block("Name der Erziehungsberechtigten"),
                block("Ich willige in die Veröffentlichung von Fotos ein und bin mit der Speicherung der Daten einverstanden"),
                block("Das Kind kann schwimmen und ist gesund"),
                block("Die Angaben des Kindes sind wie folgt zu machen"),
            ),
        ),
    )

    @Test
    fun `a German question that names the label is kept for a German form`() {
        assertThat(QuestionLanguageCheck.accepts("Wie lautet der Vorname des Kindes?", "Vorname", "de", german, "Mia")).isTrue()
        assertThat(QuestionLanguageCheck.accepts("Wie lautet der Vorname?", "Vorname", "de-DE", german)).isTrue()
    }

    @Test
    fun `an English question about a German form is rejected even when it keeps the label`() {
        assertThat(QuestionLanguageCheck.accepts("What is the Vorname of the child?", "Vorname", "de", german, "Mia")).isFalse()
        assertThat(QuestionLanguageCheck.accepts("Please provide the Vorname for this person", "Vorname", "de", german)).isFalse()
    }

    @Test
    fun `English forms and unknown languages skip the vocabulary test but keep the label and script tests`() {
        assertThat(QuestionLanguageCheck.accepts("What is the first name?", "First name", "en", emptySet())).isTrue()
        assertThat(QuestionLanguageCheck.accepts("What is the Vorname of the child?", "Vorname", null, german)).isTrue()
        assertThat(QuestionLanguageCheck.accepts("What is the first name?", "Vorname", "en", german)).isFalse() // the label is gone
    }

    @Test
    fun `a question without the label, or in another script than the label, is rejected`() {
        assertThat(QuestionLanguageCheck.accepts("Wie heißt das Kind?", "Vorname", "de", german)).isFalse()
        assertThat(QuestionLanguageCheck.accepts("What is Vorname", "الاسم", "ar", emptySet())).isFalse()
        assertThat(QuestionLanguageCheck.accepts("ما هو الاسم الأول؟", "الاسم", "ar", emptySet())).isTrue()
    }

    @Test
    fun `a label that is a sentence needs only half of its words`() {
        val label = "Hat Ihr Kind das Seepferdchen bereits?"
        assertThat(QuestionLanguageCheck.accepts("Hat Mia das Seepferdchen schon?", label, "de", german, "Mia")).isTrue()
        assertThat(QuestionLanguageCheck.accepts("Is the child able to swim?", label, "de", german)).isFalse()
    }

    @Test
    fun `punctuation and spacing do not hide the label`() {
        assertThat(QuestionLanguageCheck.accepts("Wo und wann: Ort,  Datum?", "Ort, Datum", "de", german)).isTrue()
    }
}
