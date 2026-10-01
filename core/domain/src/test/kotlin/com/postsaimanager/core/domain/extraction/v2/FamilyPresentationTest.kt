package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FamilyPresentationTest {

    @Test
    fun `every family of the v2 schema has a spec`() {
        assertThat(FamilyPresentation.familyIds).containsAtLeastElementsIn(ExtractionSchema.V2.families.map { it.id })
    }

    @Test
    fun `every slot a spec names is a slot that exists, and none is placed twice`() {
        val known = (ExtractionSchema.V2.allSlots + ExtractionSchema.DEFAULT.allSlots).map { it.json }.toSet() +
            setOf("sender", "addressee", "contact", "subject")
        for (id in FamilyPresentation.familyIds) {
            val slots = FamilyPresentation.of(id).sections.flatMap { it.slots }
            assertThat(known).containsAtLeastElementsIn(slots)
            assertThat(slots).containsNoDuplicates()
        }
    }

    @Test
    fun `a letter-like family has both address blocks, a receipt a merchant, and free form neither`() {
        for (id in listOf("official_letter", "invoice_bill", "statement", "contract_policy", "medical")) {
            val spec = FamilyPresentation.of(id)
            assertThat(spec.has(SectionKind.RECIPIENT_BLOCK)).isTrue()
            assertThat(spec.has(SectionKind.SENDER_BLOCK)).isTrue()
        }
        assertThat(FamilyPresentation.of("receipt").has(SectionKind.MERCHANT)).isTrue()
        assertThat(FamilyPresentation.of("receipt").has(SectionKind.RECIPIENT_BLOCK)).isFalse()
        assertThat(FamilyPresentation.of("free_form").has(SectionKind.SENDER_BLOCK)).isFalse()
    }

    @Test
    fun `the families that carry a recipient block in the schema have one in the presentation`() {
        for (family in ExtractionSchema.V2.families.filter { it.hasRecipientBlock }) {
            assertThat(FamilyPresentation.of(family.id).has(SectionKind.RECIPIENT_BLOCK)).isTrue()
        }
    }

    @Test
    fun `legacy ids go through the legacy mapping, unknown ones read as free form`() {
        assertThat(FamilyPresentation.familyId("reminder_dunning")).isEqualTo("invoice_bill")
        assertThat(FamilyPresentation.familyId("AUTHORITY_TAX")).isEqualTo("official_letter")
        assertThat(FamilyPresentation.familyId("invoice_bill")).isEqualTo("invoice_bill")
        assertThat(FamilyPresentation.familyId("nonsense")).isNull()
        assertThat(FamilyPresentation.familyId(null)).isNull()
        assertThat(FamilyPresentation.of("bill")).isEqualTo(FamilyPresentation.of("invoice_bill"))
        assertThat(FamilyPresentation.of("nonsense")).isEqualTo(FamilyPresentation.of("free_form"))
        assertThat(FamilyPresentation.of(null)).isEqualTo(FamilyPresentation.of("free_form"))
    }

    @Test
    fun `sectionOf finds the section a slot is listed in`() {
        val spec = FamilyPresentation.of("invoice_bill")
        assertThat(spec.sectionOf("due_date")).isEqualTo(SectionKind.ACTION)
        assertThat(spec.sectionOf("invoice_no")).isEqualTo(SectionKind.REFERENCES)
        assertThat(spec.sectionOf("letter_date")).isEqualTo(SectionKind.DATES)
        assertThat(spec.sectionOf("proof_reference")).isNull()
    }
}
