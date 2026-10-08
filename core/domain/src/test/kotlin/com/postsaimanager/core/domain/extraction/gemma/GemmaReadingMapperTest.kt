package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The mapping of a verified reading onto the pipeline's own [com.postsaimanager.core.domain.extraction.v2.RawInterpretation]. */
class GemmaReadingMapperTest {

    private val mini = MiniLetter()
    private val mapper = GemmaReadingMapper()

    private fun map(reading: GemmaReading, direction: DocDirection = DocDirection.INCOMING, forced: String? = null) =
        mapper.map(GemmaReadingVerifier().verify(reading, mini.letter, mini.offered, mini.ocrText, null), "de", direction, forced)

    private fun value(id: String, meaning: String) = GemmaValue(candidateId = id, meaning = meaning)

    @Test
    @DisplayName("the category becomes the family of the schema; document is the neutral one; a person's family wins")
    fun `family`() {
        assertThat(map(GemmaReading(category = "bill")).raw.type).isEqualTo("invoice_bill")
        assertThat(map(GemmaReading(category = "letter")).raw.type).isEqualTo("official_letter")
        assertThat(map(GemmaReading(category = "document")).raw.type).isEqualTo(ExtractionSchema.FREE_FORM.id)
        assertThat(map(GemmaReading(category = "bill"), forced = "receipt").raw.type).isEqualTo("receipt")
        // A letter the user sent has no "bill" family: the category that fits its direction is the one stored.
        assertThat(map(GemmaReading(category = "letter"), direction = DocDirection.OUTGOING).raw.type).isEqualTo("outgoing_letter")
    }

    @Test
    @DisplayName("parties become raw parties: a candidate id, or the printed line as a quote; kind and language travel with them")
    fun `parties`() {
        val raw = map(
            GemmaReading(
                sender = GemmaParty(id = "M1", kind = "company"),
                addressee = GemmaParty(id = mini.letter.lineOf("Erika"), kind = "person"),
            ),
        ).raw

        assertThat(raw.parties.map { Triple(it.role, it.id, it.kind) })
            .containsExactly(Triple("SENDER", "M1", "COMPANY"), Triple("ADDRESSEE", "Erika Mustermann", "PERSON")).inOrder()
        assertThat(raw.language).isEqualTo("de")
        assertThat(raw.universalSlots).isTrue()
    }

    @Test
    @DisplayName("a date goes to the slot its meaning names, with the meaning and the slot's own role")
    fun `dates fill slots`() {
        val raw = map(GemmaReading(dates = listOf(value("D1", "LETTER_DATE"), value("D2", "DUE_DATE"), value("D3", "DEADLINE")))).raw

        assertThat(raw.slots["letter_date"]!!.id).isEqualTo("D1")
        assertThat(raw.slots["letter_date"]!!.meaning).isEqualTo("LETTER_DATE")
        assertThat(raw.slots["letter_date"]!!.role).isEqualTo("LETTER_DATE")
        assertThat(raw.slots["due_date"]!!.id).isEqualTo("D2")
        assertThat(raw.slots["due_date"]!!.role).isEqualTo("DUE_DATE")
        assertThat(raw.slots["objection_deadline"]!!.id).isEqualTo("D3")
        assertThat(raw.extras).isEmpty()
    }

    @Test
    @DisplayName("a value whose meaning has no slot, whose slot is taken or whose meaning is other is an open value")
    fun `others are extras`() {
        val raw = map(
            GemmaReading(
                dates = listOf(value("D1", "LETTER_DATE"), value("D2", "BIRTH_DATE"), value("D3", "LETTER_DATE"), value("D5", "other")),
                amounts = listOf(value("A1", "TOTAL_DUE"), value("A2", "FEE")),
            ),
            // D5 has no valid date: it is dropped before the mapping.
        ).raw

        assertThat(raw.slots.keys).containsExactly("letter_date", "total", "fee")
        assertThat(raw.extras.map { it.id }).containsExactly("D2", "D3")
        // D3 claimed the letter's date too: the verifier left it with the first (D1) and kept D3 as an open value with no meaning.
        assertThat(raw.extras.map { it.label }).containsExactly("birth date", "date")
    }

    @Test
    @DisplayName("the best of the meanings that share a slot wins it, whatever the order the model listed them in")
    fun `priority within a slot`() {
        val raw = map(GemmaReading(amounts = listOf(value("A2", "CREDIT"), value("A1", "TOTAL_DUE")))).raw

        assertThat(raw.slots["total"]!!.id).isEqualTo("A1")
        assertThat(raw.extras.single().id).isEqualTo("A2")
    }

    @Test
    @DisplayName("an account goes to the iban slot, a reference to the slot of its kind, a contact value is open")
    fun `references`() {
        val raw = map(
            GemmaReading(
                references = listOf(
                    GemmaValue(candidateId = "I1", meaning = "iban"),
                    GemmaValue(candidateId = "N1", meaning = "customer_no"),
                    GemmaValue(candidateId = "T1", meaning = "other"),
                ),
            ),
        ).raw

        assertThat(raw.slots["iban"]!!.id).isEqualTo("I1")
        assertThat(raw.slots["customer_no"]!!.id).isEqualTo("N1")
        assertThat(raw.extras.single().let { it.id to it.label }).isEqualTo("T1" to "phone")
    }

    @Test
    @DisplayName("key facts are no part of the reading: the second step writes them, so a reading maps to none")
    fun `no key info`() {
        val raw = map(GemmaReading(keyInfo = listOf(GemmaFact("Telefon", "0800 555 0199")))).raw

        assertThat(raw.extras).isEmpty()
    }
}
