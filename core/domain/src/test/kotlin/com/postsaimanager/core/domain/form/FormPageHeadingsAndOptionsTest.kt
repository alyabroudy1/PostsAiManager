package com.postsaimanager.core.domain.form

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

/**
 * The device's second smoke: section headings and page footers became fields, and options with drawn (OCR-invisible) boxes became
 * separate text fields. The pages here are invented and shaped like that scan: headings not much larger than the text, a footer, a
 * letterhead repeated on both pages, "Ja Nein" and three time slots as separate short lines on one row.
 */
class FormPageHeadingsAndOptionsTest {

    private val finder = FindFillableFields()
    private val h = 0.014f

    private fun block(text: String, left: Float, top: Float, right: Float, height: Float = h) =
        OcrBlock(text, TextBounds(left, top, right, top + height), 0.95f, null)

    private fun page1() = listOf(
        block("SV Musterbad 1920 e.V. · Badstraße 1 · 12345 Musterstadt", 0.08f, 0.02f, 0.70f),
        block("Anmeldung zum Schwimmkurs", 0.08f, 0.07f, 0.55f, 0.024f),
        // A heading set only a little larger than the text, with a gap above it.
        block("Angaben zum Kind", 0.08f, 0.14f, 0.34f, 0.016f),
        block("Vorname: ______________", 0.08f, 0.18f, 0.45f),
        block("Nachname: ______________", 0.08f, 0.215f, 0.45f),
        block("Geburtsdatum: ______________", 0.08f, 0.25f, 0.45f),
        block("Kurswahl", 0.08f, 0.31f, 0.20f, 0.016f),
        block("Mo 16:00", 0.08f, 0.34f, 0.18f),
        block("Mi 15:00", 0.30f, 0.34f, 0.40f),
        block("Sa 10:00", 0.52f, 0.34f, 0.62f),
        block("Hat Ihr Kind das Seepferdchen bereits?", 0.08f, 0.40f, 0.48f),
        block("Ja", 0.08f, 0.43f, 0.12f),
        block("Nein", 0.30f, 0.43f, 0.36f),
        block("Seite 1 von 2", 0.43f, 0.95f, 0.57f),
        block("SV Musterbad 1920 e.V., Badstraße 1, 12345 Musterstadt", 0.08f, 0.975f, 0.70f),
    )

    private fun page2() = listOf(
        block("SV Musterbad 1920 e.V. · Badstraße 1 · 12345 Musterstadt", 0.08f, 0.02f, 0.70f),
        block("Zahlung", 0.08f, 0.10f, 0.22f, 0.016f),
        block("IBAN: ______________________", 0.08f, 0.14f, 0.55f),
        block("Seite 2 von 2", 0.43f, 0.95f, 0.57f),
        block("SV Musterbad 1920 e.V., Badstraße 1, 12345 Musterstadt", 0.08f, 0.975f, 0.70f),
    )

    private val found by lazy { finder.find(listOf(page1(), page2())) }

    @Test
    fun `a heading is a section and not a field`() {
        val labels = found.map { it.labelText }
        assertThat(labels).containsAtLeast("Vorname", "Nachname", "Geburtsdatum", "IBAN")
        assertThat(labels).doesNotContain("Angaben zum Kind")
        assertThat(labels).doesNotContain("Zahlung")
        assertThat(found.single { it.labelText == "Vorname" }.section).isEqualTo("Angaben zum Kind")
        assertThat(found.single { it.labelText == "IBAN" }.section).isEqualTo("Zahlung")
    }

    @Test
    fun `short evenly spaced items after a label are one choice with those options`() {
        val slots = found.single { it.labelText == "Kurswahl" }
        assertThat(slots.kind).isEqualTo(FormFieldKind.CHOICE)
        assertThat(slots.options).containsExactly("Mo 16:00", "Mi 15:00", "Sa 10:00").inOrder()
        val badge = found.single { it.labelText.startsWith("Hat Ihr Kind") }
        assertThat(badge.kind).isEqualTo(FormFieldKind.CHOICE)
        assertThat(badge.options).containsExactly("Ja", "Nein").inOrder()
        assertThat(found.map { it.labelText }).containsNoneOf("Ja", "Nein", "Mo 16:00", "Mi 15:00", "Sa 10:00")
    }

    @Test
    fun `page footers and a letterhead repeated on every page are not fields`() {
        assertThat(found.map { it.labelText }.filter { it.startsWith("Seite") || it.startsWith("SV Musterbad") }).isEmpty()
    }

    @Test
    fun `the fields are exactly the blanks of the form`() {
        assertThat(found.map { it.labelText }).containsExactly(
            "Vorname", "Nachname", "Geburtsdatum", "Kurswahl", "Hat Ihr Kind das Seepferdchen bereits?", "IBAN",
        ).inOrder()
    }
}
