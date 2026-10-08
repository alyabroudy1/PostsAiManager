package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** "Code verifies": each rule drops what it can show wrong, leaves the field empty and says why. */
class GemmaReadingVerifierTest {

    private val mini = MiniLetter()
    private val verifier = GemmaReadingVerifier()

    private fun verify(reading: GemmaReading, ocrText: String = mini.ocrText, letterDate: LocalDate? = LocalDate.of(2026, 9, 25)) =
        verifier.verify(reading, mini.letter, mini.offered, ocrText, letterDate)

    private fun date(id: String, meaning: String? = null) = GemmaValue(candidateId = id, meaning = meaning)

    // ── parties ──

    @Test
    @DisplayName("an id that is neither a candidate nor a line of the letter drops the party")
    fun `unknown party id`() {
        val v = verify(GemmaReading(sender = GemmaParty(id = "M99"), addressee = GemmaParty(id = "M2", kind = "person")))

        assertThat(v.parties.map { it.role }).containsExactly(PartyRole.ADDRESSEE)
        assertThat(v.drops.single()).contains("M99")
    }

    @Test
    @DisplayName("a party named by a candidate keeps its candidate id and the kind the model gave")
    fun `candidate party`() {
        val v = verify(GemmaReading(sender = GemmaParty(id = "M1", kind = "company")))

        val sender = v.parties.single()
        assertThat(sender.candidateId).isEqualTo("M1")
        assertThat(sender.kind).isEqualTo(PartyKind.COMPANY)
    }

    @Test
    @DisplayName("a party named by a line is quoted from the letter, and dropped when the line is not in its text")
    fun `line party`() {
        val line = mini.letter.lineOf("Erika Mustermann")

        assertThat(verify(GemmaReading(addressee = GemmaParty(id = line))).parties.single().quote).isEqualTo("Erika Mustermann")

        val ungrounded = verify(GemmaReading(addressee = GemmaParty(id = line)), ocrText = "something else entirely")
        assertThat(ungrounded.parties).isEmpty()
        assertThat(ungrounded.drops.single()).contains("not found in the letter")
    }

    @Test
    @DisplayName("the sender is never the addressee: the same candidate, or the same printed name, drops the addressee")
    fun `sender is not the addressee`() {
        val sameCandidate = verify(GemmaReading(sender = GemmaParty(id = "M1"), addressee = GemmaParty(id = "M1")))
        assertThat(sameCandidate.parties.map { it.role }).containsExactly(PartyRole.SENDER)

        val sameText = verify(GemmaReading(sender = GemmaParty(id = "M1"), addressee = GemmaParty(id = mini.letter.lineOf("Stadtwerke"))))
        assertThat(sameText.parties.map { it.role }).containsExactly(PartyRole.SENDER)
        assertThat(sameText.drops.single()).contains("same party as the sender")
    }

    @Test
    @DisplayName("none is an answer: no party, nothing dropped")
    fun `none`() {
        val v = verify(GemmaReading(sender = GemmaParty(id = "none"), contact = GemmaParty(id = "NONE")))

        assertThat(v.parties).isEmpty()
        assertThat(v.drops).isEmpty()
    }

    // ── dates ──

    @Test
    @DisplayName("a date that is not a calendar date, one that does not parse and an id that is not a date are dropped")
    fun `dates must parse`() {
        val v = verify(GemmaReading(dates = listOf(date("D1", "LETTER_DATE"), date("D4"), date("D5"), date("A1"), date("D77"))))

        assertThat(v.dates.map { it.candidate.id }).containsExactly("D1")
        assertThat(v.drops).hasSize(4)
    }

    @Test
    @DisplayName("a meaning the registry does not know is stored as no meaning, not dropped")
    fun `unknown meaning`() {
        val v = verify(GemmaReading(dates = listOf(date("D1", "BIRTHDAY_PARTY"), date("D2", "other"))))

        assertThat(v.dates.map { it.meaningId }).containsExactly(null, null)
    }

    @Test
    @DisplayName("a due date before the letter's date is dropped; the letter's date is the model's LETTER_DATE, else the one code found")
    fun `due not before the letter`() {
        val named = verify(GemmaReading(dates = listOf(date("D1", "LETTER_DATE"), date("D3", "DUE_DATE"), date("D2", "DUE_DATE"))), letterDate = null)
        assertThat(named.dates.map { it.candidate.id }).containsExactly("D1", "D2").inOrder()

        val found = verify(GemmaReading(dates = listOf(date("D3", "DUE_DATE"), date("D2", "DUE_DATE"))))
        assertThat(found.dates.map { it.candidate.id }).containsExactly("D2")
        assertThat(found.drops.single()).contains("before the letter's date")
    }

    @Test
    @DisplayName("with no letter date at all the rule has nothing to compare, and the due date stays")
    fun `no letter date`() {
        val v = verify(GemmaReading(dates = listOf(date("D3", "DUE_DATE"))), letterDate = null)

        assertThat(v.dates.map { it.candidate.id }).containsExactly("D3")
    }

    // ── amounts ──

    @Test
    @DisplayName("an amount that does not parse to money is dropped; one that does keeps its meaning")
    fun `amounts must parse`() {
        val v = verify(GemmaReading(amounts = listOf(date("A1", "TOTAL_DUE"), date("A3", "FEE"), date("D1", "FEE"))))

        assertThat(v.amounts.map { it.candidate.id to it.meaningId }).containsExactly("A1" to "TOTAL_DUE")
        assertThat(v.drops).hasSize(2)
    }

    // ── references ──

    @Test
    @DisplayName("an account is kept only as an IBAN with a right checksum")
    fun `iban checksum`() {
        fun ref(id: String, kind: String) = GemmaValue(candidateId = id, meaning = kind)

        val v = verify(GemmaReading(references = listOf(ref("I1", "iban"), ref("I2", "iban"), ref("N1", "iban"), ref("I1", "invoice_no"))))

        assertThat(v.references.map { it.candidate.id to it.kind }).containsExactly("I1" to "iban")
        assertThat(v.drops).hasSize(3)
    }

    @Test
    @DisplayName("a reference number keeps its kind; a phone is only other")
    fun `reference kinds`() {
        fun ref(id: String, kind: String) = GemmaValue(candidateId = id, meaning = kind)

        val v = verify(GemmaReading(references = listOf(ref("N1", "customer_no"), ref("T1", "other"), ref("T1", "customer_no"))))

        assertThat(v.references.map { it.candidate.id to it.kind }).containsExactly("N1" to "customer_no", "T1" to "other")
    }

    // ── actions ──

    @Test
    @DisplayName("an action keeps its kind; the date and the amount it points at only when they were kept")
    fun `actions`() {
        val v = verify(
            GemmaReading(
                dates = listOf(date("D2", "DUE_DATE"), date("D4")),
                amounts = listOf(date("A1", "TOTAL_DUE")),
                actions = listOf(
                    GemmaAction("pay", dateId = "D2", amountId = "A1"),
                    GemmaAction("reply", dateId = "D4", amountId = "none"),
                    GemmaAction("dance"),
                    GemmaAction("pay", dateId = "D2"),
                ),
            ),
        )

        assertThat(v.actions.map { Triple(it.kind, it.dateCandidateId, it.amountCandidateId) })
            .containsExactly(Triple("pay", "D2", "A1"), Triple("reply", null, null)).inOrder()
        assertThat(v.drops.any { it.contains("dance") }).isTrue()
    }

    // ── free texts and the rest ──

    @Test
    @DisplayName("the category is a registry id, anything else is document")
    fun `category`() {
        assertThat(verify(GemmaReading(category = "bill")).category).isEqualTo("bill")
        assertThat(verify(GemmaReading(category = "spaceship")).category).isEqualTo("document")
        assertThat(verify(GemmaReading(category = null)).category).isEqualTo("document")
    }

    @Test
    @DisplayName("the language is a short code, anything else is dropped")
    fun `language`() {
        assertThat(verify(GemmaReading(language = "de")).language).isEqualTo("de")
        assertThat(verify(GemmaReading(language = "German please")).language).isNull()
    }

    @Test
    @DisplayName("the name must be grounded in the letter: a number it does not hold drops it")
    fun `name`() {
        assertThat(verify(GemmaReading(name = "Zahlungserinnerung Rechnung")).name).isEqualTo("Zahlungserinnerung Rechnung")
        assertThat(verify(GemmaReading(name = "Mahnung 2031")).name).isNull()
    }

    @Test
    @DisplayName("the summary is dropped when it states a number the letter does not hold")
    fun `summary`() {
        val good = "Der Anbieter bittet um Zahlung von 64,98 € bis zum 09.10.2026."
        assertThat(verify(GemmaReading(summary = good)).summary).isEqualTo(good)
        assertThat(verify(GemmaReading(summary = "Der Anbieter bittet um Zahlung von 99,99 €.")).summary).isNull()
    }

    @Test
    @DisplayName("a key fact stays only when its value is in the letter and is not a value the reading already holds")
    fun `key info`() {
        val v = verify(
            GemmaReading(
                references = listOf(GemmaValue(candidateId = "N1", meaning = "customer_no")),
                keyInfo = listOf(
                    GemmaFact("Telefon", "0800 555 0199"),
                    GemmaFact("Kundennummer", "4402917"),
                    GemmaFact("Konto", "DE99 0000 0000 0000 0000 00"),
                ),
            ),
        )

        assertThat(v.keyInfo.map { it.label }).containsExactly("Telefon")
        assertThat(v.drops.last()).contains("2 key fact")
    }
}
