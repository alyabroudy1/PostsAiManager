package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FamilyRegistryTest {

    private val schema = ExtractionSchema.DEFAULT

    @Test
    fun `the registry has the families and topics of the architecture`() {
        assertThat(schema.families.map { it.id }).containsExactly(
            "official_letter", "invoice_bill", "receipt", "form_application", "statement", "contract_policy", "certificate_id", "medical",
            "ticket_booking","outgoing_letter", "payment_proof", "free_form",
        ).inOrder()
        assertThat(schema.topics.map { it.id }).containsExactly(
            "government", "tax", "health", "insurance", "bank_finance", "housing_utilities", "work", "school_education", "vehicle",
            "telecom", "shopping", "travel", "legal", "personal",
        ).inOrder()
    }

    @Test
    fun `every family has the core slots first and a content description`() {
        for (f in schema.families) {
            assertThat(f.slots.take(Slots.CORE.size)).isEqualTo(Slots.CORE)
            assertThat(f.description).isNotEmpty()
        }
        for (t in schema.topics) assertThat(t.description).isNotEmpty()
    }

    @Test
    fun `free_form is never scored, for any direction`() {
        for (direction in DocDirection.entries) assertThat(schema.familiesFor(direction).map { it.id }).doesNotContain("free_form")
        assertThat(ExtractionSchema.FREE_FORM.slots).isEqualTo(Slots.CORE)
    }

    @Test
    fun `a received letter is scored against 9 families, and never the outgoing letter or the proof`() {
        val incoming = schema.familiesFor(DocDirection.INCOMING).map { it.id }
        assertThat(incoming).hasSize(9)
        assertThat(incoming).containsNoneOf("outgoing_letter", "payment_proof", "free_form")
        assertThat(incoming.size + schema.topics.size).isAtMost(24)
        assertThat(schema.familiesFor(DocDirection.OUTGOING).map { it.id }).containsExactly("outgoing_letter")
        assertThat(schema.familiesFor(DocDirection.PROOF).map { it.id }).containsExactly("payment_proof")
    }

    @Test
    fun `the outgoing letter and the payment proof keep their directions`() {
        assertThat(ExtractionSchema.OUTGOING_LETTER.directions).containsExactly(DocDirection.OUTGOING)
        assertThat(ExtractionSchema.PAYMENT_PROOF.directions).containsExactly(DocDirection.PROOF)
    }

    @Test
    fun `the recipient block belongs to the letter-like families`() {
        val withBlock = schema.families.filter { it.hasRecipientBlock }.map { it.id }
        assertThat(withBlock).containsExactly("official_letter", "invoice_bill", "statement", "contract_policy", "medical")
    }

    @Test
    fun `slotsFor is the family's slots alone without topics`() {
        assertThat(schema.slotsFor(ExtractionSchema.INVOICE_BILL, emptyList())).isEqualTo(ExtractionSchema.INVOICE_BILL.slots)
        assertThat(schema.slotsFor(ExtractionSchema.FREE_FORM, emptyList())).isEqualTo(Slots.CORE)
    }

    @Test
    fun `slotsFor adds the slots of a topic without repeating one`() {
        val slots = schema.slotsFor(ExtractionSchema.OFFICIAL_LETTER, listOf("tax"))
        assertThat(slots).containsAtLeastElementsIn(ExtractionSchema.OFFICIAL_LETTER.slots)
        assertThat(slots).containsAtLeast(Slots.TAX_NO, Slots.CASE_NO)
        assertThat(slots).containsNoDuplicates()
        // the family already has OBJECTION_DEADLINE: the government topic adds only the case number
        val government = schema.slotsFor(ExtractionSchema.OFFICIAL_LETTER, listOf("government"))
        assertThat(government.count { it == Slots.OBJECTION_DEADLINE }).isEqualTo(1)
    }

    @Test
    fun `only the best two topics contribute slots`() {
        val slots = schema.slotsFor(ExtractionSchema.FREE_FORM, listOf("tax", "insurance", "school_education"))
        assertThat(slots).containsAtLeast(Slots.TAX_NO, Slots.POLICY_NO)
        assertThat(slots).doesNotContain(Slots.EVENT_DATE)
    }

    @Test
    fun `an unknown topic is skipped and does not use up a place`() {
        val slots = schema.slotsFor(ExtractionSchema.FREE_FORM, listOf("astrology", "tax", "insurance"))
        assertThat(slots).containsAtLeast(Slots.TAX_NO, Slots.POLICY_NO)
    }

    @Test
    fun `a topic without slots adds none`() {
        assertThat(schema.slotsFor(ExtractionSchema.RECEIPT, listOf("shopping"))).isEqualTo(ExtractionSchema.RECEIPT.slots)
    }

    @Test
    fun `medical and the health topic are sensitive, and nothing else is`() {
        assertThat(schema.isSensitive("medical", emptyList())).isTrue()
        assertThat(schema.isSensitive("invoice_bill", listOf("insurance", "health"))).isTrue()
        assertThat(schema.isSensitive("invoice_bill", listOf("insurance"))).isFalse()
        assertThat(schema.isSensitive(null, emptyList())).isFalse()
        assertThat(schema.isSensitive("nonsense", listOf("nonsense"))).isFalse()
        assertThat(schema.families.filter { it.sensitive }.map { it.id }).containsExactly("medical")
        assertThat(schema.topics.filter { it.sensitive }.map { it.id }).containsExactly("health")
    }

    @Test
    fun `the live schema holds the families only, so a legacy type id is none and the medical family is sensitive`() {
        assertThat(ExtractionSchema.DEFAULT.isSensitive("medical", emptyList())).isTrue()
        val familyIds = ExtractionSchema.DEFAULT.families.map { it.id }.toSet()
        for (legacy in LegacyTypes.BY_TYPE.keys - familyIds) assertThat(ExtractionSchema.DEFAULT.family(legacy)).isNull()
        assertThat(ExtractionSchema.DEFAULT.families.map { it.id }).containsExactlyElementsIn(schema.families.map { it.id })
    }

    @Test
    fun `the abstain family is the one unscored family`() {
        assertThat(ExtractionSchema.DEFAULT.abstain).isEqualTo(ExtractionSchema.FREE_FORM)
    }

    @Test
    fun `every legacy type maps onto a family and topics the schema knows`() {
        for ((type, mapping) in LegacyTypes.BY_TYPE) {
            assertThat(schema.family(mapping.family)).isNotNull()
            for (topic in mapping.topics) assertThat(schema.topic(topic)).isNotNull()
            assertThat(LegacyTypes.of(type)).isEqualTo(mapping)
        }
    }

    @Test
    fun `LegacyTypes leaves a family id and an unknown id alone`() {
        assertThat(LegacyTypes.of("invoice_bill")).isNull()
        assertThat(LegacyTypes.of("nonsense")).isNull()
        assertThat(LegacyTypes.of(null)).isNull()
        assertThat(LegacyTypes.of(" Health ")?.family).isEqualTo("medical")
    }
}
