package com.postsaimanager.core.domain.extraction.actions

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.TicketSlot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ActionKindReaderTest {

    private val slots = listOf(
        TicketSlot("letter_date", "Document Date", "28.09.2026"),
        TicketSlot("total", "Amount", "1.284,50 €"),
        TicketSlot("due_date", "Deadline", "15.10.2026"),
        TicketSlot("contract_end", "Contract End", "31.12.2026"),
        TicketSlot("iban", "IBAN", "DE89 3704 0044 0532 0130 00"),
        TicketSlot("invoice_no", "Invoice Number", "RE-2026-0815"),
        TicketSlot("fee", "Fee", "5,00 €"),
    )

    /** A scorer that gives each question the score a test writes for the text it contains; everything else scores [other]. */
    private class Scripted(val other: Double = -4.0, val rules: List<Pair<String, Double>>) : ActionScorer {
        val batches = ArrayList<Pair<String, List<String>>>()
        override suspend fun score(name: String, questions: List<String>): List<Double> {
            batches += name to questions
            return questions.map { q -> rules.firstOrNull { q.contains(it.first) }?.second ?: other }
        }
    }

    private fun read(scorer: ActionScorer, profile: ActionKindProfile = ActionKindProfile(), senderKnown: Boolean = true, list: List<TicketSlot> = slots) =
        runBlocking { ActionKindReader(scorer, profile).read(list, senderKnown) }

    @Test
    fun `a payment letter chooses pay and binds the deadline, the amount, the sender, the account and the reference`() {
        val scorer = Scripted(
            rules = listOf(
                ActionQuestions.anything() to 3.0,
                ActionQuestions.kind(ActionKinds.PAY) to 4.0,
                "Is «Deadline: 15.10.2026» the date by which the reader is asked to pay" to 2.0,
                "Is «Amount: 1.284,50 €» the amount the reader is asked to pay" to 2.0,
            ),
        )
        val reading = read(scorer)!!
        assertThat(reading.items).containsExactly(
            ActionItem(
                "pay",
                mapOf("date" to "due_date", "amount" to "total", "party" to "sender", "reference" to "invoice_no", "iban" to "iban"),
            ),
        )
        assertThat(reading.chosen).containsExactly("pay")
        assertThat(reading.any).isEqualTo(3.0)
    }

    @Test
    fun `only stored values are ever offered to the model, and the date of the letter itself is not among them`() {
        val scorer = Scripted(rules = listOf(ActionQuestions.anything() to 3.0, ActionQuestions.kind(ActionKinds.PAY) to 4.0))
        read(scorer)
        val bindings = scorer.batches.single { it.first == ActionKindReader.BINDINGS_BATCH }.second
        // Each date slot (not the document date) and each amount slot is asked about, as «label: value».
        assertThat(bindings).contains("Is «Deadline: 15.10.2026» the date by which the reader is asked to pay? Answer:")
        assertThat(bindings).contains("Is «Contract End: 31.12.2026» the date by which the reader is asked to pay? Answer:")
        assertThat(bindings).contains("Is «Amount: 1.284,50 €» the amount the reader is asked to pay? Answer:")
        assertThat(bindings).contains("Is «Fee: 5,00 €» the amount the reader is asked to pay? Answer:")
        assertThat(bindings.none { it.contains("Document Date") }).isTrue()
        assertThat(bindings.none { it.contains("IBAN") || it.contains("Invoice Number") }).isTrue()
    }

    @Test
    fun `of several dates the one the model scores highest above the threshold is bound`() {
        val scorer = Scripted(
            rules = listOf(
                ActionQuestions.anything() to 3.0,
                ActionQuestions.kind(ActionKinds.PAY) to 4.0,
                "Is «Contract End: 31.12.2026» the date by which" to 0.4,
                "Is «Deadline: 15.10.2026» the date by which" to 1.5,
                "Is «Amount: 1.284,50 €» the amount" to -0.2,
                "Is «Fee: 5,00 €» the amount" to -1.0,
            ),
        )
        val item = read(scorer)!!.items.single()
        assertThat(item.bindings["date"]).isEqualTo("due_date")
        // No amount scored above the threshold: the line is shorter, nothing is guessed.
        assertThat(item.bindings).doesNotContainKey("amount")
    }

    @Test
    fun `a date that no stored value is scored above the threshold for is left out`() {
        val scorer = Scripted(rules = listOf(ActionQuestions.anything() to 3.0, ActionQuestions.kind(ActionKinds.PAY) to 4.0))
        val item = read(scorer)!!.items.single()
        assertThat(item.bindings).doesNotContainKey("date")
        assertThat(item.bindings).doesNotContainKey("amount")
        // The party, the account and the reference are stored values, not scored.
        assertThat(item.bindings).containsAtLeast("party", "sender", "iban", "iban", "reference", "invoice_no")
    }

    @Test
    fun `no sender stored means the action names no party`() {
        val scorer = Scripted(rules = listOf(ActionQuestions.anything() to 3.0, ActionQuestions.kind(ActionKinds.REPLY) to 4.0))
        assertThat(read(scorer, senderKnown = false)!!.items.single().bindings).doesNotContainKey("party")
        assertThat(read(scorer, senderKnown = true)!!.items.single().bindings).containsEntry("party", "sender")
    }

    @Test
    fun `a letter that asks nothing gives no actions and scores no binding`() {
        val scorer = Scripted(rules = listOf(ActionQuestions.anything() to -2.0, ActionQuestions.kind(ActionKinds.PAY) to 4.0))
        val reading = read(scorer)!!
        assertThat(reading.items).isEmpty()
        assertThat(scorer.batches.map { it.first }).containsExactly(ActionKindReader.KINDS_BATCH)
    }

    @Test
    fun `at most two actions are chosen`() {
        val scorer = Scripted(
            rules = listOf(
                ActionQuestions.anything() to 3.0,
                ActionQuestions.kind(ActionKinds.PAY) to 4.0,
                ActionQuestions.kind(ActionKinds.REPLY) to 4.0,
                ActionQuestions.kind(ActionKinds.CONTACT) to 4.0,
            ),
        )
        assertThat(read(scorer)!!.items.map { it.kind }).hasSize(2)
    }

    @Test
    fun `a failed scoring of the kinds gives null so the stored actions stay, a failed binding batch gives shorter lines`() {
        val failing = ActionScorer { _, _ -> null }
        assertThat(read(failing)).isNull()
        val failsBindings = object : ActionScorer {
            override suspend fun score(name: String, questions: List<String>): List<Double>? =
                if (name == ActionKindReader.KINDS_BATCH) questions.map { if (it == ActionQuestions.kind(ActionKinds.PAY)) 4.0 else if (it == ActionQuestions.anything()) 3.0 else -4.0 } else null
        }
        val item = read(failsBindings)!!.items.single()
        assertThat(item.bindings.keys).doesNotContain("date")
        assertThat(item.bindings.keys).doesNotContain("amount")
        assertThat(item.kind).isEqualTo("pay")
    }

    @Test
    fun `a recording run scores every stored date and amount under every kind even when nothing is chosen`() {
        val scorer = Scripted(rules = listOf(ActionQuestions.anything() to -5.0))
        val reading = read(scorer, ActionKindProfile(scoreEveryBinding = true))!!
        assertThat(reading.items).isEmpty()
        val bindings = scorer.batches.single { it.first == ActionKindReader.BINDINGS_BATCH }.second
        // Dates: the two stored (the deadline, the contract end) under every kind that states a date; amounts: the two stored under pay only.
        assertThat(bindings.count { it.contains("the amount the reader is asked to pay") }).isEqualTo(2)
        assertThat(bindings.count { it.contains("Is «Deadline: 15.10.2026»") }).isEqualTo(ActionKinds.ALL.count { it.dateMeaning != null })
    }

    @Test
    fun `the trace names ids and numbers only, never a stored value`() {
        val trace = ArrayList<String>()
        val scorer = Scripted(rules = listOf(ActionQuestions.anything() to 3.0, ActionQuestions.kind(ActionKinds.PAY) to 4.0))
        runBlocking { ActionKindReader(scorer, trace = { trace += it }).read(slots, true) }
        assertThat(trace).isNotEmpty()
        trace.forEach { line ->
            assertThat(line).doesNotContain("1.284")
            assertThat(line).doesNotContain("15.10.2026")
        }
    }
}
