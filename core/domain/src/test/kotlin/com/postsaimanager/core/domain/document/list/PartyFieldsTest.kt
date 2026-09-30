package com.postsaimanager.core.domain.document.list

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import org.junit.jupiter.api.Test

class PartyFieldsTest {

    private var next = 0
    private fun field(slot: String?, name: String, value: String) = ExtractedData(
        id = "f${next++}", documentId = "d1", fieldName = name, fieldValue = value, fieldType = ExtractedFieldType.TEXT,
        confidence = 0.9f, slotKey = slot,
    )

    @Test
    fun `the slot wins over the legacy names`() {
        val legacy = field(null, UnderstandingToFields.SENDER_ORGANISATION, "Old Corp")
        val slot = field(UnderstandingToFields.SLOT_SENDER, "sender", "Stadtwerke")
        assertThat(PartyFields.sender(listOf(legacy, slot))).isSameInstanceAs(slot)
    }

    @Test
    fun `an older reading falls back to the organisation, then the sender name`() {
        val person = field(null, UnderstandingToFields.SENDER_NAME, "Max")
        val org = field(null, UnderstandingToFields.SENDER_ORGANISATION, "Old Corp")
        assertThat(PartyFields.sender(listOf(person, org))).isSameInstanceAs(org)
        assertThat(PartyFields.sender(listOf(person))).isSameInstanceAs(person)
    }

    @Test
    fun `the addressee falls back to the receiver name and nothing else`() {
        val legacy = field(null, UnderstandingToFields.RECEIVER_NAME, "Erika")
        assertThat(PartyFields.addressee(listOf(legacy))).isSameInstanceAs(legacy)
        assertThat(PartyFields.addressee(listOf(field(null, "other", "x")))).isNull()
        assertThat(PartyFields.of(DocumentParty.ADDRESSEE, listOf(legacy))).isSameInstanceAs(legacy)
    }
}
