package com.postsaimanager.core.domain.form

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

class FindFillableFieldsTest {

    private val finder = FindFillableFields()

    private fun block(text: String, left: Float, top: Float, right: Float, bottom: Float = top + 0.014f) =
        OcrBlock(text, TextBounds(left, top, right, bottom), 0.95f, null)

    private fun List<FieldCandidate>.labels() = map { it.labelText }

    @Test
    fun `the German form yields its fill runs, option groups and checkbox with their sections`() {
        val found = finder.find(FormFixtures.pages(FormFixtures.GERMAN))
        val strong = found.filter { it.strong }

        assertThat(strong.labels()).containsExactly(
            "Name des Kindes", "Geburtsdatum", "Geburtsort", "Anschrift",
            "Hat Ihr Kind das Seepferdchen bereits?", "Kurstermin",
            "Name der Erziehungsberechtigten", "Telefon (Notfall)", "E-Mail",
            "Kontoinhaber", "IBAN", "Name der Bank", "Ich willige in die Veröffentlichung von Fotos ein", "Ort, Datum", "Unterschrift",
        ).inOrder()
        assertThat(found.map { it.orderIndex }).isEqualTo(found.indices.toList())

        val badge = found.single { it.labelText.startsWith("Hat Ihr Kind") }
        assertThat(badge.kind).isEqualTo(FormFieldKind.CHOICE)
        assertThat(badge.options).containsExactly("Ja", "Nein").inOrder()
        val slot = found.single { it.labelText == "Kurstermin" }
        assertThat(slot.options).containsExactly("Montag 16:00 Uhr", "Mittwoch 15:00 Uhr", "Samstag 10:00 Uhr").inOrder()
        assertThat(found.single { it.labelText.startsWith("Ich willige") }.kind).isEqualTo(FormFieldKind.CHECKBOX)

        assertThat(found.single { it.labelText == "Name des Kindes" }.section).isEqualTo("Angaben zum Kind")
        assertThat(found.single { it.labelText == "Name der Erziehungsberechtigten" }.section).isEqualTo("Erziehungsberechtigte")
        assertThat(found.single { it.labelText == "IBAN" }.section).isEqualTo("Zahlung per Lastschrift")
        assertThat(found.single { it.labelText == "IBAN" }.page).isEqualTo(2)
    }

    @Test
    fun `the signature captions under two empty lines become the labels of the lines`() {
        val found = finder.find(FormFixtures.pages(FormFixtures.GERMAN))
        val place = found.single { it.labelText == "Ort, Datum" }
        val signature = found.single { it.labelText == "Unterschrift" }
        assertThat(place.fillBox!!.right).isLessThan(signature.fillBox!!.left)
        assertThat(place.fillBox!!.bottom).isLessThan(place.labelBox!!.top + 0.001f)
    }

    @Test
    fun `a label with empty space after it is a weak candidate and a paragraph line is none`() {
        val found = finder.find(FormFixtures.pages(FormFixtures.GERMAN))
        val allergies = found.single { it.labelText.startsWith("Allergien") }
        assertThat(allergies.strong).isFalse()
        assertThat(allergies.evidence).isEqualTo(FieldEvidence.LABEL_SPACE)
        assertThat(allergies.fillBox!!.left).isGreaterThan(allergies.labelBox!!.right - 0.001f)
        assertThat(allergies.fillBox!!.right).isGreaterThan(0.9f)
        assertThat(found.labels().none { it.startsWith("Ich erkläre") || it.startsWith("Die Haftung") }).isTrue()
    }

    @Test
    fun `the English form yields separate checkboxes and the empty cells under a table header`() {
        val found = finder.find(FormFixtures.pages(FormFixtures.ENGLISH))
        val boxes = found.filter { it.kind == FormFieldKind.CHECKBOX }
        assertThat(boxes.labels()).containsExactly(
            "I give permission for my child to take part in the trip", "I consent to my child being photographed",
        ).inOrder()
        val cells = found.filter { it.kind == FormFieldKind.TABLE_CELL }
        assertThat(cells.labels()).containsExactly("Payment method", "Amount", "Date paid").inOrder()
        assertThat(cells.all { !it.strong && it.fillBox!!.top >= it.labelBox!!.bottom }).isTrue()
        assertThat(found.labels()).containsAtLeast("Pupil's full name", "Signed", "Date")
        assertThat(found.single { it.labelText == "Medical conditions or allergies" }.evidence).isEqualTo(FieldEvidence.LABEL_SPACE)
    }

    @Test
    fun `the Arabic form is read right to left, with the blank on the left of its label`() {
        val found = finder.find(FormFixtures.pages(FormFixtures.ARABIC))
        val name = found.single { it.labelText == "الاسم الكامل" }
        assertThat(name.evidence).isEqualTo(FieldEvidence.FILL_RUN)
        assertThat(name.fillBox!!.right).isAtMost(name.labelBox!!.left + 0.001f)

        val membership = found.single { it.labelText == "نوع العضوية" }
        assertThat(membership.kind).isEqualTo(FormFieldKind.CHOICE)
        assertThat(membership.options).containsExactly("شهرية", "سنوية").inOrder()

        val address = found.single { it.labelText == "العنوان" }
        assertThat(address.fillBox!!.right).isAtMost(address.labelBox!!.left + 0.001f)
        assertThat(found.single { it.labelText == "اسم صاحب الحساب" }.section).isEqualTo("الدفع")
    }

    @Test
    fun `text written inside a fill run, or after a colon label, is reported as already filled`() {
        val pages = listOf(
            listOf(
                block("Vorname: ____________________", 0.08f, 0.20f, 0.50f),
                block("Ahmad", 0.30f, 0.200f, 0.38f),
                block("Nachname:", 0.08f, 0.26f, 0.20f),
                block("Mustermann", 0.215f, 0.26f, 0.34f),
            ),
        )
        val found = finder.find(pages)
        assertThat(found.single { it.labelText == "Vorname" }.alreadyFilled).isEqualTo("Ahmad")
        assertThat(found.single { it.labelText == "Nachname" }.alreadyFilled).isEqualTo("Mustermann")
    }

    @Test
    fun `a lone letter O read for a box glyph groups its row into a choice`() {
        val pages = listOf(
            listOf(
                block("Mitglied im Verein", 0.08f, 0.30f, 0.30f),
                block("O", 0.08f, 0.33f, 0.095f),
                block("Ja", 0.105f, 0.33f, 0.14f),
                block("O", 0.20f, 0.33f, 0.215f),
                block("Nein", 0.225f, 0.33f, 0.28f),
            ),
        )
        val found = finder.find(pages)
        val choice = found.single()
        assertThat(choice.kind).isEqualTo(FormFieldKind.CHOICE)
        assertThat(choice.labelText).isEqualTo("Mitglied im Verein")
        assertThat(choice.options).containsExactly("Ja", "Nein").inOrder()
    }

    @Test
    fun `a section without a heading on its page carries over from the page before`() {
        val pages = listOf(
            listOf(
                block("Teilnehmer", 0.08f, 0.10f, 0.30f, 0.125f),
                block("Name: ______________", 0.08f, 0.20f, 0.40f),
            ),
            listOf(
                block("Telefon: ______________", 0.08f, 0.20f, 0.40f),
            ),
        )
        val found = finder.find(pages)
        val second = found.single { it.page == 2 }
        assertThat(second.section).isEqualTo("Teilnehmer")
        assertThat(second.sectionPage).isEqualTo(1)
        assertThat(second.continuesSection).isTrue()
        assertThat(found.single { it.page == 1 }.continuesSection).isFalse()
    }

    @Test
    fun `a page with no blanks yields no candidates`() {
        val pages = listOf(
            listOf(
                block("Sehr geehrte Damen und Herren, wir bestätigen Ihre Anmeldung zum Kurs und freuen uns auf Sie.", 0.08f, 0.20f, 0.92f),
                block("Mit freundlichen Grüßen aus dem Schwimmbad Beispieldorf und allen Kursleitern.", 0.08f, 0.22f, 0.90f),
            ),
        )
        assertThat(finder.find(pages)).isEmpty()
    }
}
