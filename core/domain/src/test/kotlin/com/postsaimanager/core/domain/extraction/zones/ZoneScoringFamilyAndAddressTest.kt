package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.Letter
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.model.AddressPart
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * What the scoring interpreter decides besides the slots: the category and the topics (decided LAST, in the second stage, in one scored
 * batch after the facts the first stage read; a category a person gave is context, not a switch), the structured address of a letter with an
 * address field, the runner-ups of every scored question, and that no title is asked.
 */
class ZoneScoringFamilyAndAddressTest {

    private val letter = Letters.invoice

    private fun family(id: String) = "Is this document ${ExtractionSchema.DEFAULT.family(id)!!.description}? Answer:"

    private fun topic(id: String) = "Does this document concern ${ExtractionSchema.DEFAULT.topic(id)!!.description}? Answer:"

    private class Run(val result: ExtractionV2Result, val session: FakePromptSession, val interpreter: ZoneScoringInterpreter)

    private fun run(
        letter: Letter = this.letter,
        profile: ScoringProfile = ScoringProfile(),
        topicsInFirstStage: Boolean = false,
        forcedFamily: String? = null,
        stages: ExtractionV2Pipeline.Stages = ExtractionV2Pipeline.Stages.ALL,
        written: (String) -> String? = { q -> if (q.contains("BCP-47")) "de" else "\"text\"" },
        scorer: (String) -> Double,
    ): Run {
        val session = FakePromptSession().apply {
            this.scorer = scorer
            responder = { q, _ -> written(q) }
        }
        val interpreter = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096, profile = profile, topicsInFirstStage = topicsInFirstStage)
        val result = runBlocking { ExtractionV2Pipeline().run(letter.pages, interpreter, 4096, stages = stages, forcedFamily = forcedFamily) }
        return Run(result, session, interpreter)
    }

    private fun answers(vararg yes: String): (String) -> Double = { c -> if (yes.any { c.contains(it) }) 5.0 else -5.0 }

    // ── the family and the topics ──

    @Test
    fun `the category and the topics are one scored batch in the second stage, recorded as score family`() {
        val r = run(scorer = answers(family("invoice_bill"), topic("tax")))
        val batch = r.interpreter.transcript.single { it.name == "score:family" }
        val categories = ExtractionSchema.DEFAULT.categoryFamilies(com.postsaimanager.core.domain.extraction.v2.DocDirection.INCOMING).size
        assertThat(categories).isEqualTo(9)
        assertThat(batch.question.split(ZoneScoringInterpreter.BATCH_SEPARATOR)).hasSize(categories + ExtractionSchema.DEFAULT.topics.size)
        assertThat(batch.answer!!.split(',')).hasSize(categories + ExtractionSchema.DEFAULT.topics.size)
        assertThat(r.result.documentType).isEqualTo(ExtractionSchema.INVOICE_BILL)
        assertThat(r.result.topics).containsExactly("tax")
        // The old type batch is gone: nothing is called `score:type`.
        assertThat(r.interpreter.transcript.none { it.name == "score:type" }).isTrue()
    }

    @Test
    fun `the category is scored after the slots and the parties, and its questions carry what was read`() {
        val r = run(scorer = answers(family("invoice_bill"), "«Musterfirma GmbH»"))
        val names = r.interpreter.transcript.map { it.name }
        // Everything the first stage reads comes before the category.
        assertThat(names.indexOf("score:family")).isGreaterThan(names.indexOf("score:slot:total"))
        assertThat(names.indexOf("score:family")).isGreaterThan(names.indexOf("score:sender"))
        val categoryQuestions = r.interpreter.transcript.single { it.name == "score:family" }.question
            .split(ZoneScoringInterpreter.BATCH_SEPARATOR).filter { it.contains("Is this document ") }
        assertThat(categoryQuestions).hasSize(9)
        assertThat(categoryQuestions.all { it.startsWith("WHAT WAS READ FROM THIS DOCUMENT") }).isTrue()
        assertThat(categoryQuestions.all { it.contains("- sender: Musterfirma GmbH") }).isTrue()
    }

    @Test
    fun `a document no category passes is the abstain family, and every question was asked all the same`() {
        val r = run(profile = ScoringProfile(thresholds = mapOf(ScoringProfile.FAMILY to 0.0))) { -5.0 }
        assertThat(r.result.documentType).isEqualTo(ExtractionSchema.FREE_FORM)
        // free_form is never scored, whatever the model says.
        assertThat(r.session.scored.flatten().none { it.contains(ExtractionSchema.FREE_FORM.description) }).isTrue()
        // The type removes no question: the slots of the families a received letter can be are asked of the general "Document" too.
        val asked = r.interpreter.transcript.map { it.name }.filter { it.startsWith("score:slot:") }.map { it.removePrefix("score:slot:") }
        assertThat(asked).containsAtLeast("fee", "original_due_date", "appointment", "objection_deadline", "total", "due_date")
    }

    @Test
    fun `a category that does not beat the made-up baseline is the abstain family`() {
        val withBaseline = ScoringProfile(thresholds = mapOf(ScoringProfile.FAMILY to 0.0), categoryBaselineMargin = 0.0)
        val baseline = "Is this document ${ScoringDescriptions.CATEGORY_BASELINE}? Answer:"
        val beaten = run(profile = withBaseline) { c -> if (c.contains(family("invoice_bill"))) 1.0 else if (c.contains(baseline)) 2.0 else -5.0 }
        assertThat(beaten.result.documentType).isEqualTo(ExtractionSchema.FREE_FORM)
        val clear = run(profile = withBaseline) { c -> if (c.contains(family("invoice_bill"))) 1.0 else if (c.contains(baseline)) -2.0 else -5.0 }
        assertThat(clear.result.documentType).isEqualTo(ExtractionSchema.INVOICE_BILL)
    }

    @Test
    fun `a value for a slot of some kind of document is kept and stored whatever the type came out as, the first stage's neutral Document included`() {
        // N1 is a reminder with a fee. The first stage does not know the type (it is the neutral Document) and the fee is not a core slot.
        val yes: (String) -> Boolean = { c -> c.contains("Is «5,00") && c.contains("the fee") }
        val first = run(letter = Letters.n1, stages = ExtractionV2Pipeline.Stages.FIRST) { c -> if (yes(c)) 5.0 else -5.0 }
        assertThat(first.result.documentType).isEqualTo(ExtractionSchema.FREE_FORM)
        val fee = first.result.slots.entries.firstOrNull { it.key.json == "fee" }
        assertThat(fee).isNotNull()
        assertThat(fee!!.value.normalized).isEqualTo("5.00 EUR")
        assertThat(first.result.diagnostics.rejections.none { it.contains("does not belong") }).isTrue()
        // It reaches the fields the document stores.
        assertThat(com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter().adapt(first.result).facts.map { it.label }).contains("Fee")
        // And when the category is decided (second stage), the same value is still there.
        val all = run(letter = Letters.n1) { c -> if (yes(c) || c.contains(family("invoice_bill"))) 5.0 else -5.0 }
        assertThat(all.result.documentType).isEqualTo(ExtractionSchema.INVOICE_BILL)
        assertThat(all.result.slots.keys.map { it.json }).contains("fee")
    }

    @Test
    fun `an invoice the classifier filed as an official letter is still asked for its reference numbers`() {
        val r = run(scorer = answers(family("official_letter")))
        assertThat(r.result.documentType).isEqualTo(ExtractionSchema.OFFICIAL_LETTER)
        val asked = r.interpreter.transcript.map { it.name }.filter { it.startsWith("score:slot:") }.map { it.removePrefix("score:slot:") }.toSet()
        assertThat(asked).containsAtLeast("invoice_no", "customer_no", "total", "due_date", "iban", "fee", "original_due_date")
    }

    @Test
    fun `the slots are those every family of the direction has plus the best two topics, whatever the category`() {
        val r = run(scorer = answers(family("official_letter"), topic("tax"), topic("government"), topic("insurance")))
        val asked = r.interpreter.transcript.map { it.name }.filter { it.startsWith("score:slot:") }.map { it.removePrefix("score:slot:") }.toSet()
        assertThat(asked).containsAtLeast("objection_deadline", "tax_no", "case_no", "previous_amount", "appointment", "contract_end", "receipt_no")
        // Only the slots of an outgoing letter or a proof of payment are not asked of a received letter.
        assertThat(asked).containsNoneOf("recipient_org", "sent_date", "cited_references", "proof_amount")
    }

    @Test
    fun `a category a person gave is context for every question and is not decided again`() {
        val r = run(forcedFamily = "receipt", scorer = answers(family("invoice_bill"), topic("shopping")))
        assertThat(r.result.documentType).isEqualTo(ExtractionSchema.RECEIPT)
        // Not decided: no category question is scored, whatever the model would say.
        assertThat(r.session.scored.flatten().none { it.contains("Is this document ") }).isTrue()
        // The topics are still scored, on their own.
        assertThat(r.result.topics).containsExactly("shopping")
        // Context: every session opened for the reading starts with the person's word, and none asks fewer questions because of it.
        assertThat(r.session.opens).isNotEmpty()
        assertThat(r.session.opens.all { it.contains("The user says this document is a receipt.") }).isTrue()
        val asked = r.interpreter.transcript.map { it.name }.filter { it.startsWith("score:slot:") }.map { it.removePrefix("score:slot:") }.toSet()
        assertThat(asked).containsAtLeast("invoice_no", "fee", "receipt_no")
        // It reaches the name written for the document too.
        assertThat(r.session.asks.single { it.question.contains("Write a short name for THIS document") }.question)
            .contains("The user says this document is a receipt.")
    }

    @Test
    fun `a family id the schema does not know is no context`() {
        val r = run(forcedFamily = "spaceship", scorer = answers(family("invoice_bill")))
        assertThat(r.result.documentType).isEqualTo(ExtractionSchema.INVOICE_BILL)
        assertThat(r.session.opens.none { it.contains("The user says") }).isTrue()
    }

    @Test
    fun `the first stage decides no type and scores no category or topic, and the second stage does`() {
        val first = run(stages = ExtractionV2Pipeline.Stages.FIRST, scorer = answers(family("invoice_bill"), topic("tax")))
        assertThat(first.session.scored.flatten().none { it.contains("Does this document concern") || it.contains("Is this document ") }).isTrue()
        assertThat(first.result.topics).isEmpty()
        // The neutral "Document" until the second stage has read it; the ticket carries it.
        assertThat(first.result.documentType).isEqualTo(ExtractionSchema.FREE_FORM)
        assertThat(first.result.enrichment?.typeId).isEqualTo("free_form")
        assertThat(first.interpreter.transcript.none { it.name == "score:family" }).isTrue()

        val all = run(scorer = answers(family("invoice_bill"), topic("tax")))
        assertThat(all.result.topics).containsExactly("tax")
        assertThat(all.interpreter.transcript.count { it.name == "score:family" }).isEqualTo(1)
    }

    @Test
    fun `with the topics kept in the first stage they are scored there, on their own`() {
        val r = run(topicsInFirstStage = true, scorer = answers(family("invoice_bill"), topic("tax")))
        val batches = r.interpreter.transcript.filter { it.name == "score:family" }
        assertThat(batches).hasSize(2)
        assertThat(batches.first().question.split(ZoneScoringInterpreter.BATCH_SEPARATOR)).hasSize(ExtractionSchema.DEFAULT.topics.size)
        assertThat(r.result.topics).containsExactly("tax")
        assertThat(r.result.documentType).isEqualTo(ExtractionSchema.INVOICE_BILL)
    }

    // ── the structured address ──

    @Test
    fun `a letter with a recipient block gets the addressee's and the sender's structured address`() {
        // The address is read because the reading found an addressee.
        val r = run { c ->
            if (c.contains(family("invoice_bill")) || c.contains("the name of a private person") || (c.contains("«Erika Mustermann»") && c.contains("the addressee"))) 5.0 else -5.0
        }
        assertThat(r.result.parties.addressees.map { it.name }).containsExactly("Erika Mustermann")
        val addressee = r.result.addresses[PartyRole.ADDRESSEE]
        assertThat(addressee).isNotNull()
        assertThat(addressee!!.postcode?.value).isEqualTo("54321")
        assertThat(addressee.city?.value).isEqualTo("Beispieldorf")
        assertThat(addressee.street?.value).isEqualTo("Musterstraße")
        assertThat(addressee.houseNumber?.value).isEqualTo("12")
        assertThat(AddressPart.RECIPIENT_NAME.key).isNotEmpty()
        // The labels were scored as grids and recorded one per statement, so a replay can answer them.
        assertThat(r.interpreter.transcript.any { it.name == "score:addr" }).isTrue()
    }

    @Test
    fun `a document with no addressee reads no address`() {
        // Decided by what the reading found (an addressee), not by the category, which is not known when the address is read.
        val r = run(letter = Letters.receipt, scorer = answers(family("receipt")))
        assertThat(r.result.documentType).isEqualTo(ExtractionSchema.RECEIPT)
        assertThat(r.result.addresses).isEmpty()
        assertThat(r.interpreter.transcript.none { it.name == "score:addr" }).isTrue()
    }

    @Test
    fun `the address cells are bounded by the budget`() {
        val r = run(scorer = answers(family("invoice_bill")))
        val cells = r.interpreter.transcript.filter { it.name == "score:addr" }.sumOf { it.answer!!.split(',').size }
        assertThat(cells).isAtMost(com.postsaimanager.core.domain.extraction.address.AddressBudget().recipientCells + com.postsaimanager.core.domain.extraction.address.AddressBudget().senderCells)
    }

    // ── the runners-up ──

    @Test
    fun `every scored question keeps its runners-up as alternatives, best first, never the chosen value`() {
        // The total scores +5 for one amount, +2 for the net, +1 for every other amount: the best three of the rest are its alternatives.
        val r = run { c ->
            when {
                c.contains(family("invoice_bill")) -> 5.0
                c.contains("the main amount") && c.contains("«1.284,50 €»") -> 5.0
                c.contains("the main amount") && c.contains("«1.079,41") -> 2.0
                c.contains("the main amount") -> 1.0
                c.contains("the sender") && c.contains("«Musterfirma GmbH»") -> 5.0
                c.contains("the sender") -> 1.0
                else -> -5.0
            }
        }
        val total = r.result.slots.entries.first { it.key.json == "total" }.value
        assertThat(total.normalized).isEqualTo("1284.50 EUR")
        assertThat(total.alternatives).isNotEmpty()
        assertThat(total.alternatives.size).isAtMost(3)
        assertThat(total.alternatives.first().score).isEqualTo(2.0f)
        assertThat(total.alternatives.map { it.score!! }).isInOrder(Comparator.reverseOrder<Float>())
        assertThat(total.alternatives.map { it.normalized }).doesNotContain("1284.50 EUR")
        assertThat(total.alternatives.map { it.normalized }.toSet()).hasSize(total.alternatives.size)
        // The chip knows where the value sits, and the stored field carries the chips (the adapter's provenance).
        assertThat(total.alternatives.first().page).isNotNull()
        val stored = com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter().adapt(r.result).facts.first { it.provenance?.slotKey == "total" }
        assertThat(stored.provenance!!.alternatives).isEqualTo(total.alternatives)
        // A party has at most three too.
        assertThat(r.result.parties.sender!!.value.alternatives.size).isAtMost(3)
    }

    // ── what is not asked, and what is written ──

    @Test
    fun `no title is asked and the summary is written by the summary writer from verified facts`() {
        // A summary that states an amount the letter never printed: the gate rejects it twice and the template stands in.
        val r = run(
            written = { q -> if (q.contains("BCP-47")) "de" else if (q.contains("FACTS (verified")) "\"Der Betrag 999,99 € ist fällig.\"" else "\"text\"" },
            scorer = answers(family("invoice_bill"), "«Musterfirma GmbH»"),
        )
        assertThat(r.session.asks.none { it.question.contains("title", ignoreCase = true) && !it.question.contains("FACTS") }).isTrue()
        assertThat(r.interpreter.transcript.none { it.name == "text:title" || it.name == "text:other" }).isTrue()
        val summary = r.interpreter.transcript.filter { it.name == "text:summary" }
        assertThat(summary).hasSize(2)
        assertThat(summary.first().question).contains("FACTS (verified; use only these):")
        assertThat(summary.last().question).contains("Do not copy any line of the letter")
        assertThat(r.result.summary?.code).isEqualTo("template")
        assertThat(r.result.composedTitle?.args?.first()).isEqualTo("invoice_bill")
    }
}
