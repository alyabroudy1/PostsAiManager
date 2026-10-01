package com.postsaimanager.feature.documents

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.address.AddressRows
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.model.AddressPart
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
        assertThat(SlotLabels.typeIds).containsAtLeastElementsIn(ExtractionSchema.DEFAULT.families.map { it.id })
    }

    @Test
    fun `every family and topic of extraction-v2-2 has a label, and every slot they add has one`() {
        assertThat(SlotLabels.typeIds).containsAtLeastElementsIn(ExtractionSchema.V2.families.map { it.id })
        assertThat(SlotLabels.topicIds).containsExactlyElementsIn(ExtractionSchema.V2.topics.map { it.id })
        assertThat(SlotLabels.slotKeys).containsAtLeastElementsIn(ExtractionSchema.V2.allSlots.map { it.json })
        assertThat(SlotLabels.type("medical")).isEqualTo(R.string.doctype_medical)
        assertThat(SlotLabels.topic("health")).isEqualTo(R.string.topic_health)
        assertThat(SlotLabels.topic("astrology")).isNull()
    }

    @Test
    fun `every address row that AddressRows can store has a label for both roles`() {
        val parts = AddressPart.entries.map { it.key }.filter { it != AddressPart.RECIPIENT_NAME.key } + AddressPart.RECIPIENT_NAME.key + AddressRows.RAW
        for (role in listOf(PartyRole.ADDRESSEE, PartyRole.SENDER)) {
            for (part in parts) {
                val key = AddressRows.prefixOf(role) + part
                assertThat(SlotLabels.slot(key)).isNotNull()
            }
        }
        assertThat(SlotLabels.slot("addressee.street")).isNotEqualTo(SlotLabels.slot("sender.street"))
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
