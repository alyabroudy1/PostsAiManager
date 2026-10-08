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

    @Test
    @DisplayName("an action whose only date the model called the letter's date is dropped (a filing date is not a deadline)")
    fun `invented action on a date that is no deadline`() {
        val v = verify(
            GemmaReading(
                dates = listOf(date("D1", "LETTER_DATE"), date("D2", "other")),
                actions = listOf(GemmaAction("confirm_renew", dateId = "D2")),
            ),
        )

        assertThat(v.actions).isEmpty()
        assertThat(v.drops.any { it.contains("confirm_renew") && it.contains("not a deadline") }).isTrue()
    }

    @Test
    @DisplayName("an action keeps its amount when only its date had a meaning that is no deadline; deadline meanings stay")
    fun `action date meanings`() {
        val v = verify(
            GemmaReading(
                dates = listOf(date("D1", "LETTER_DATE"), date("D2", "DEADLINE"), date("D3", "APPOINTMENT")),
                amounts = listOf(date("A1", "TOTAL_DUE")),
                actions = listOf(
                    GemmaAction("pay", dateId = "D1", amountId = "A1"),
                    GemmaAction("object_cancel", dateId = "D2"),
                    GemmaAction("attend", dateId = "D3"),
                ),
            ),
        )

        assertThat(v.actions.map { Triple(it.kind, it.dateCandidateId, it.amountCandidateId) })
            .containsExactly(Triple("pay", null, "A1"), Triple("object_cancel", "D2", null), Triple("attend", "D3", null)).inOrder()
    }

    // ── asksReader: the model's own answers must agree ──

    @Test
    @DisplayName("a letter the model says asks nothing of its reader has no action, whatever actions it listed")
    fun `asks reader no drops every action`() {
        val v = verify(
            GemmaReading(
                asksReader = false,
                dates = listOf(date("D2", "DUE_DATE")),
                amounts = listOf(date("A1", "TOTAL_DUE")),
                actions = listOf(GemmaAction("pay", dateId = "D2", amountId = "A1"), GemmaAction("object_cancel")),
            ),
        )

        assertThat(v.actions).isEmpty()
        assertThat(v.asksReader).isFalse()
        assertThat(v.drops.any { it.contains("2 action(s) dropped") }).isTrue()
        // Only the actions go: the values the model decided stay.
        assertThat(v.dates.map { it.candidate.id }).containsExactly("D2")
        assertThat(v.amounts.map { it.candidate.id }).containsExactly("A1")
    }

    @Test
    @DisplayName("a letter that asks something keeps its actions; so does an answer with no asksReader (nothing to contradict)")
    fun `asks reader yes or missing keeps actions`() {
        val actions = listOf(GemmaAction("pay"))

        assertThat(verify(GemmaReading(asksReader = true, actions = actions)).actions.map { it.kind }).containsExactly("pay")
        assertThat(verify(GemmaReading(asksReader = null, actions = actions)).actions.map { it.kind }).containsExactly("pay")
    }

    @Test
    @DisplayName("asksReader no with no action drops nothing")
    fun `asks reader no without actions`() {
        assertThat(verify(GemmaReading(asksReader = false)).drops).isEmpty()
    }

    // ── a meaning only one value can have ──

    @Test
    @DisplayName("two amounts that both claim the amount to pay: the one listed first keeps it, the other is kept as other")
    fun `duplicate exclusive amount meaning`() {
        val v = verify(GemmaReading(amounts = listOf(date("A1", "TOTAL_DUE"), date("A2", "TOTAL_DUE"))))

        assertThat(v.amounts.map { it.candidate.id to it.meaningId }).containsExactly("A1" to "TOTAL_DUE", "A2" to null).inOrder()
        assertThat(v.drops.single()).contains("TOTAL_DUE")
    }

    @Test
    @DisplayName("a meaning other values may share (a premium, a fee) is not exclusive: both keep it")
    fun `shared amount meaning`() {
        val v = verify(GemmaReading(amounts = listOf(date("A1", "PREMIUM"), date("A2", "PREMIUM"))))

        assertThat(v.amounts.map { it.meaningId }).containsExactly("PREMIUM", "PREMIUM")
        assertThat(v.drops).isEmpty()
    }

    @Test
    @DisplayName("an amount the model marked other stays without a meaning, and does not take the amount-to-pay from the one that has it")
    fun `other is no claim`() {
        val v = verify(GemmaReading(amounts = listOf(date("A2", "other"), date("A1", "TOTAL_DUE"))))

        assertThat(v.amounts.map { it.candidate.id to it.meaningId }).containsExactly("A2" to null, "A1" to "TOTAL_DUE").inOrder()
        assertThat(v.drops).isEmpty()
    }

    @Test
    @DisplayName("the letter's date is exclusive too: two dates claiming it leave it with the first")
    fun `duplicate letter date`() {
        val v = verify(GemmaReading(dates = listOf(date("D1", "LETTER_DATE"), date("D3", "LETTER_DATE"))))

        assertThat(v.dates.map { it.candidate.id to it.meaningId }).containsExactly("D1" to "LETTER_DATE", "D3" to null).inOrder()
    }

    // ── the timeline event kind ──

    @Test
    @DisplayName("the event kind is a kind of the timeline registry; any other word is no kind")
    fun `event kind`() {
        assertThat(verify(GemmaReading(eventKind = "approval")).eventKind).isEqualTo("approval")
        assertThat(verify(GemmaReading(eventKind = "information")).eventKind).isEqualTo("information")
        val unknown = verify(GemmaReading(eventKind = "party"))
        assertThat(unknown.eventKind).isNull()
        assertThat(unknown.drops.single()).contains("event kind")
        assertThat(verify(GemmaReading()).eventKind).isNull()
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

    // ── paid: the model's own answers must agree ──

    @Test
    @DisplayName("a document the model says is already paid has no pay action, though it listed one; the other actions stay")
    fun `already paid drops the pay action`() {
        val v = verify(
            GemmaReading(
                asksReader = true,
                paid = PaidState.ALREADY_PAID,
                dates = listOf(date("D2", "APPOINTMENT")),
                amounts = listOf(date("A1", "INVOICE_TOTAL")),
                actions = listOf(GemmaAction("pay", dateId = "none", amountId = "A1"), GemmaAction("attend", dateId = "D2")),
            ),
        )

        assertThat(v.actions.map { it.kind }).containsExactly("attend")
        assertThat(v.paid).isEqualTo(PaidState.ALREADY_PAID)
        assertThat(v.drops.any { it.contains("pay") && it.contains("already paid") }).isTrue()
    }

    @Test
    @DisplayName("an already paid document has no amount to pay: the amount the model called so becomes the invoice total, the rest is untouched")
    fun `already paid has no total due`() {
        val v = verify(GemmaReading(paid = PaidState.ALREADY_PAID, amounts = listOf(date("A1", "TOTAL_DUE"), date("A2", "FEE"))))

        assertThat(v.amounts.map { it.candidate.id to it.meaningId }).containsExactly("A1" to "INVOICE_TOTAL", "A2" to "FEE").inOrder()
        assertThat(v.drops.single()).contains("TOTAL_DUE")
    }

    @Test
    @DisplayName("the invoice total is exclusive: when the model already named another amount the invoice total, the converted one is kept as other")
    fun `already paid with a total already named`() {
        val v = verify(GemmaReading(paid = PaidState.ALREADY_PAID, amounts = listOf(date("A2", "INVOICE_TOTAL"), date("A1", "TOTAL_DUE"))))

        assertThat(v.amounts.map { it.candidate.id to it.meaningId }).containsExactly("A2" to "INVOICE_TOTAL", "A1" to null).inOrder()
    }

    @Test
    @DisplayName("an already paid document has no date by which to pay: that date is an ordinary date; other date meanings stay")
    fun `already paid has no due date`() {
        val v = verify(GemmaReading(paid = PaidState.ALREADY_PAID, dates = listOf(date("D1", "LETTER_DATE"), date("D2", "DUE_DATE"))))

        assertThat(v.dates.map { it.candidate.id to it.meaningId }).containsExactly("D1" to "LETTER_DATE", "D2" to null).inOrder()
    }

    @Test
    @DisplayName("a document that is still to pay, one without payment and one with no answer keep their pay action and amount to pay")
    fun `not paid keeps everything`() {
        for (paid in listOf(PaidState.TO_PAY, PaidState.NOT_APPLICABLE, null)) {
            val v = verify(
                GemmaReading(
                    paid = paid,
                    dates = listOf(date("D2", "DUE_DATE")),
                    amounts = listOf(date("A1", "TOTAL_DUE")),
                    actions = listOf(GemmaAction("pay", dateId = "D2", amountId = "A1")),
                ),
            )

            assertThat(v.actions.map { it.kind }).containsExactly("pay")
            assertThat(v.amounts.single().meaningId).isEqualTo("TOTAL_DUE")
            assertThat(v.dates.single().meaningId).isEqualTo("DUE_DATE")
            assertThat(v.drops).isEmpty()
        }
    }

    @Test
    @DisplayName("already paid does not decide the category: a receipt and a settled bill are both allowed, the model chooses")
    fun `paid does not decide the category`() {
        assertThat(verify(GemmaReading(paid = PaidState.ALREADY_PAID, category = "receipt")).category).isEqualTo("receipt")
        assertThat(verify(GemmaReading(paid = PaidState.ALREADY_PAID, category = "bill")).category).isEqualTo("bill")
    }

    // ── the amount to pay is one field ──

    @Test
    @DisplayName("the amount to pay is the one amount of its own field, with the meaning of the amount to pay, and the list keeps the others")
    fun `to pay field`() {
        val v = verify(GemmaReading(toPayId = "A1", amounts = listOf(date("A2", "FEE"))))

        assertThat(v.amounts.map { it.candidate.id to it.meaningId }).containsExactly("A1" to "TOTAL_DUE", "A2" to "FEE").inOrder()
        assertThat(v.drops).isEmpty()
    }

    @Test
    @DisplayName("the field wins over a list entry for the same amount, and no second amount can take the meaning from it")
    fun `to pay wins over the list`() {
        val v = verify(GemmaReading(toPayId = "A1", amounts = listOf(date("A1", "FEE"), date("A2", "TOTAL_DUE"))))

        assertThat(v.amounts.map { it.candidate.id to it.meaningId }).containsExactly("A1" to "TOTAL_DUE", "A2" to null).inOrder()
    }

    @Test
    @DisplayName("none and no answer give no amount to pay; an id that is no amount of the letter is dropped with its reason")
    fun `to pay none or wrong`() {
        assertThat(verify(GemmaReading(toPayId = null, amounts = listOf(date("A2", "FEE")))).amounts.map { it.meaningId }).containsExactly("FEE")
        val wrong = verify(GemmaReading(toPayId = "D1"))
        assertThat(wrong.amounts).isEmpty()
        assertThat(wrong.losses.single()).contains("D1")
    }

    @Test
    @DisplayName("for a document the model says is already paid, the amount to pay is the total that was paid")
    fun `to pay on a receipt`() {
        val v = verify(GemmaReading(paid = PaidState.ALREADY_PAID, toPayId = "A1"))

        assertThat(v.amounts.single().meaningId).isEqualTo("INVOICE_TOTAL")
    }

    // ── drops are values to check, never silent ──

    @Test
    @DisplayName("a value the model chose that a check refused is kept as a value to check, with the reason, and the reading is told what was lost")
    fun `refused values are to check`() {
        val v = verify(GemmaReading(dates = listOf(date("D4", "DUE_DATE")), amounts = listOf(date("A3", "FEE")), references = listOf(GemmaValue("I2", meaning = "iban"))))

        assertThat(v.dates).isEmpty()
        assertThat(v.toCheck.mapNotNull { it.value?.id }).containsExactly("D4", "A3", "I2")
        assertThat(v.toCheck.all { it.reason.isNotBlank() }).isTrue()
        assertThat(v.losses).hasSize(3)
    }

    @Test
    @DisplayName("a party named by a line the letter does not hold is a party to check; a correction that keeps the value is no loss")
    fun `ungrounded party is to check, a correction is no loss`() {
        val line = mini.letter.lineOf("Erika Mustermann")
        val v = verify(GemmaReading(addressee = GemmaParty(id = line), language = "not a code"), ocrText = "something else entirely")

        assertThat(v.toCheck.single().party?.role).isEqualTo(PartyRole.ADDRESSEE)
        assertThat(v.toCheck.single().party?.quote).isEqualTo("Erika Mustermann")
        // The language that is no code is only corrected: it is in the log but is no loss.
        assertThat(v.drops).hasSize(2)
        assertThat(v.losses).hasSize(1)
    }
}
