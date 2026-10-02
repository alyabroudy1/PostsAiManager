package com.postsaimanager.core.domain.extraction.address

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import org.junit.jupiter.api.Test

class AddressVerifierTest {

    private val labeler = AddressLineLabeler()
    private val verifier = AddressVerifier()

    /** A block read by shape only, with the word-only line settled as the recipient name by a party. */
    private fun labeled(lines: List<AddressLine>) = labeler.shape(lines, com.postsaimanager.core.domain.extraction.v2.Parties(listOf(party("Erika Mustermann"))))

    private val complete = listOf(
        line("Erika Mustermann", 0.10f), line("Musterstraße 12", 0.12f), line("54321 Beispieldorf", 0.14f),
    )

    @Test
    fun `a complete block of a known country verifies and keeps the labellers confidence`() {
        val a = verifier.verify(labeled(complete), listOf("Erika Mustermann"))
        assertThat(a.verified).isTrue()
        assertThat(a.notes).isEmpty()
        assertThat(a.postcode!!.confidence).isEqualTo(ConfidenceCombiner.HIGH)
        assertThat(a.recipientNames.single().confidence).isEqualTo(ConfidenceCombiner.HIGH)
        assertThat(a.postcode!!.bbox).isNotNull()
    }

    @Test
    fun `a passed check never raises the confidence the labeller gave`() {
        val a = verifier.verify(labeled(complete))
        // the street was found by shape alone (MEDIUM); verifying the rest of the block does not lift it
        assertThat(a.street!!.confidence).isEqualTo(ConfidenceCombiner.MEDIUM)
    }

    @Test
    fun `a missing required part is noted and caps every part`() {
        val a = verifier.verify(labeled(listOf(line("Erika Mustermann", 0.10f), line("54321 Beispieldorf", 0.12f))))
        assertThat(a.verified).isFalse()
        assertThat(a.notes).contains("missing:street")
        assertThat(a.postcode!!.confidence).isAtMost(ConfidenceCombiner.Caps.INCONSISTENT)
    }

    @Test
    fun `lines that are not one block are capped`() {
        val a = verifier.verify(labeled(listOf(line("Erika Mustermann", 0.10f), line("Musterstraße 12", 0.12f), line("54321 Beispieldorf", 0.30f))))
        assertThat(a.verified).isFalse()
        assertThat(a.notes).contains(AddressVerifier.NOTE_NOT_ONE_BLOCK)
        assertThat(a.city!!.confidence).isAtMost(ConfidenceCombiner.Caps.INCONSISTENT)
    }

    @Test
    fun `an addressee that is not among the name lines caps the name parts`() {
        val a = verifier.verify(labeled(complete), listOf("Somebody Else"))
        assertThat(a.notes).contains(AddressVerifier.NOTE_NAME_NOT_FOUND)
        assertThat(a.recipientNames.single().confidence).isAtMost(ConfidenceCombiner.Caps.ROLE_MISMATCH)
        // the rest of the address is not punished for it
        assertThat(a.postcode!!.confidence).isEqualTo(ConfidenceCombiner.HIGH)
    }

    @Test
    fun `an addressee name is found through ocr noise`() {
        val a = verifier.verify(labeled(complete), listOf("Erika Mustermann"))
        assertThat(a.notes).doesNotContain(AddressVerifier.NOTE_NAME_NOT_FOUND)
    }

    @Test
    fun `a country line that contradicts the postcode shape finds no postcode and is not verified`() {
        val lines = listOf(line("Erika Mustermann", 0.10f), line("Musterstraße 12", 0.12f), line("54321 Beispieldorf", 0.14f), line("United Kingdom", 0.16f))
        val labeled = labeler.shape(lines)
        assertThat(labeled.format?.iso2).isEqualTo("GB")
        val a = verifier.verify(labeled)
        assertThat(a.verified).isFalse()
        assertThat(a.notes).contains("missing:postcode")
    }

    @Test
    fun `a postcode that does not have the countrys shape is capped as invalid`() {
        val lines = listOf(line("Musterstraße 12", 0.12f), line("5432 Beispieldorf", 0.14f))
        val de = AddressFormats.of("DE")
        val labeled = LabeledAddress(
            lines, listOf(LabeledPart(com.postsaimanager.core.model.AddressPart.POSTCODE, "5432", 1, ConfidenceCombiner.HIGH)),
            de, CountrySource.LINE, null, true, emptyList(),
        )
        val a = verifier.verify(labeled)
        assertThat(a.notes).contains(AddressVerifier.NOTE_POSTCODE_SHAPE)
        assertThat(a.postcode!!.confidence).isAtMost(ConfidenceCombiner.Caps.INVALID)
        assertThat(a.verified).isFalse()
    }

    @Test
    fun `no known country caps every part at medium`() {
        val lines = listOf(line("Jean Dupont", 0.10f), line("10 Rue de la Paix", 0.12f), line("75002 Paris", 0.14f), line("Frankreich", 0.16f))
        val a = verifier.verify(labeler.shape(lines))
        assertThat(a.notes).contains(AddressVerifier.NOTE_COUNTRY_UNKNOWN)
        assertThat(a.verified).isFalse()
        assertThat(a.parts.all { it.second.confidence <= ConfidenceCombiner.MEDIUM }).isTrue()
    }

    @Test
    fun `the raw lines are always kept`() {
        val a = verifier.verify(labeler.shape(listOf(line("Irgendein Text", 0.10f), line("Noch einer", 0.12f))))
        assertThat(a.lines).containsExactly("Irgendein Text", "Noch einer").inOrder()
        assertThat(a.verified).isFalse()
    }
}
