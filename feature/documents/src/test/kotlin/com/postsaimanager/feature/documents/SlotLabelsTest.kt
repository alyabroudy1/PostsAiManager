package com.postsaimanager.feature.documents

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ValueSource
import org.junit.jupiter.api.Test

class SlotLabelsTest {

    private fun field(name: String, slotKey: String?, source: ValueSource = ValueSource.MACHINE) = ExtractedData(
        id = "f", documentId = "d", fieldName = name, fieldValue = "v", fieldType = ExtractedFieldType.OTHER,
        confidence = 0.9f, slotKey = slotKey, source = source,
    )

    @Test
    fun `every slot and every document type in the schema has a label to render`() {
        val slotKeys = ExtractionSchema.DEFAULT.allSlots.map { it.json }
        assertThat(SlotLabels.slotKeys).containsAtLeastElementsIn(slotKeys)
        assertThat(SlotLabels.typeIds).containsAtLeastElementsIn(ExtractionSchema.DEFAULT.types.map { it.id })
    }

    @Test
    fun `the people and the subject have labels too`() {
        assertThat(SlotLabels.slotKeys).containsAtLeast("sender", "addressee", "contact", "subject")
    }

    @Test
    fun `a machine value is rendered from its slot key whatever its stored name`() {
        assertThat(SlotLabels.labelFor(field("Sender Organization", "sender"))).isEqualTo(R.string.slot_sender)
        assertThat(SlotLabels.labelFor(field("Amount", "total"))).isEqualTo(R.string.slot_total)
    }

    @Test
    fun `a value a person took over keeps a name they typed, but still localises a name the app wrote`() {
        assertThat(SlotLabels.labelFor(field("Amount", "total", ValueSource.USER))).isEqualTo(R.string.slot_total)
        assertThat(SlotLabels.labelFor(field("Sender Name", "sender", ValueSource.USER))).isEqualTo(R.string.slot_sender)
        assertThat(SlotLabels.labelFor(field("What I owe", "total", ValueSource.USER))).isNull()
    }

    @Test
    fun `an extra, an unknown key and a row without a slot show their stored name`() {
        assertThat(SlotLabels.labelFor(field("Zaehlernummer", "x:zaehlernummer"))).isNull()
        assertThat(SlotLabels.labelFor(field("Whatever", "some_future_slot"))).isNull()
        assertThat(SlotLabels.labelFor(field("Amount", null))).isNull()
    }

    @Test
    fun `a found value is worded from its kind and number, an unlabelled one from its key`() {
        assertThat(SlotLabels.found("found:DATE:1")).isEqualTo(SlotLabels.FoundLabel(R.string.found_date, 1))
        assertThat(SlotLabels.found("found:AMOUNT:3")).isEqualTo(SlotLabels.FoundLabel(R.string.found_amount, 3))
        assertThat(SlotLabels.found("total")).isNull()
        assertThat(SlotLabels.labelFor(field("unlabelled", "unlabelled"))).isEqualTo(R.string.slot_unlabelled)
    }

    @Test
    fun `an extra stored under its bare key shows the key's words`() {
        assertThat(SlotLabels.extraKeyName("x:amount")).isEqualTo("amount")
        assertThat(SlotLabels.extraKeyName("x:geleistete_vorauszahlungen")).isEqualTo("geleistete vorauszahlungen")
        assertThat(SlotLabels.extraKeyName("Zaehlernummer")).isNull()
    }
}
