package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.KeySlot
import com.postsaimanager.core.model.SourceType
import org.junit.jupiter.api.Test

class KeySlotMarkerTest {

    private fun row(id: String, slotKey: String?, value: String = "v", importance: Float? = null, deleted: Boolean = false) = ExtractedData(
        id = id, documentId = "d", fieldName = id, fieldValue = value, fieldType = ExtractedFieldType.OTHER, confidence = 0.9f,
        slotKey = slotKey, importance = importance, deletedByUser = deleted,
    )

    @Test
    fun `a picked slot row gets the score, an unpicked one stays unmarked`() {
        val changed = KeySlotMarker.mark(
            listOf(row("a", "invoice_no"), row("b", "total"), row("c", "iban")),
            listOf(KeySlot("invoice_no", 2.5f), KeySlot("iban", 0.5f)),
        )
        assertThat(changed.associate { it.slotKey to it.importance }).containsExactly("invoice_no", 2.5f, "iban", 0.5f)
        assertThat(changed.all { it.isKeySlot }).isTrue()
    }

    @Test
    fun `the newest reading decides, so a pick an earlier reading gave is taken back`() {
        val changed = KeySlotMarker.mark(listOf(row("a", "invoice_no", importance = 1f), row("b", "total", importance = 3f)), listOf(KeySlot("total", 3f)))
        assertThat(changed.map { it.slotKey }).containsExactly("invoice_no")
        assertThat(changed.single().importance).isNull()
    }

    @Test
    fun `a stage that did not score the slots changes nothing, and one that picked none clears the picks`() {
        val stored = listOf(row("a", "invoice_no", importance = 1f))
        assertThat(KeySlotMarker.mark(stored, null)).isEmpty()
        assertThat(KeySlotMarker.mark(stored, emptyList()).single().importance).isNull()
    }

    @Test
    fun `extras and rows without a slot key are never marked`() {
        val changed = KeySlotMarker.mark(listOf(row("x", "x:tarif"), row("y", null)), listOf(KeySlot("x:tarif", 2f)))
        assertThat(changed).isEmpty()
    }

    @Test
    fun `the rebuilt ticket carries the live slot rows with the schema's label, not the extras, parties or ignored rows`() {
        val slots = EnrichmentTicketRebuilder.slotsOf(
            listOf(
                row("a", "invoice_no", "R-1"), row("b", "x:tarif", "Komfort"), row("c", "sender", "Firma"),
                row("d", "iban", "DE89", deleted = true), row("e", "total", "  64,98 €  "),
            ),
        )
        assertThat(slots.map { it.key to it.value }).containsExactly("invoice_no" to "R-1", "total" to "64,98 €").inOrder()
        assertThat(slots.first { it.key == "total" }.label).isEqualTo("Amount")
        val doc = Document(id = "d", title = "T", sourceType = SourceType.CAMERA, createdAt = 0, modifiedAt = 0, extractionType = "invoice_bill")
        assertThat(EnrichmentTicketRebuilder.rebuild(doc, listOf(row("a", "invoice_no", "R-1"))).slots.map { it.key }).containsExactly("invoice_no")
    }
}
