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
 * What the scoring interpreter decides besides the slots: the family and the topics (one scored batch, a family a person chose, topics
 * in the second stage), the structured address of a letter with a recipient block, the runner-ups of every scored question, and that no
 * title is asked.
 */
class ZoneScoringFamilyAndAddressTest {

    private val letter = Letters.invoice

    private fun family(id: String) = "Is this document ${ExtractionSchema.DEFAULT.family(id)!!.description}? Answer:"

    private fun topic(id: String) = "Does this document concern ${ExtractionSchema.DEFAULT.topic(id)!!.description}? Answer:"

    private class Run(val result: ExtractionV2Result, val session: FakePromptSession, val interpreter: ZoneScoringInterpreter)

    private fun run(
        letter: Letter = this.letter,
        profile: ScoringProfile = ScoringProfile(),
        topicsInFirstStage: Boolean = true,
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
    fun `the family and the topics are one scored batch, recorded as score family`() {
        val r = run(scorer = answers(family("invoice_bill"), topic("tax")))
        val batch = r.interpreter.transcript.single { it.name == "score:family" }
        val incoming = ExtractionSchema.DEFAULT.familiesFor(com.postsaimanager.core.domain.extraction.v2.DocDirection.INCOMING).size
        assertThat(batch.question.split(ZoneScoringInterpreter.BATCH_SEPARATOR)).hasSize(incoming + ExtractionSchema.DEFAULT.topics.size)
        assertThat(batch.answer!!.split(',')).hasSize(incoming + ExtractionSchema.DEFAULT.topics.size)
        assertThat(r.result.documentType).isEqualTo(ExtractionSchema.INVOICE_BILL)
        assertThat(r.result.topics).containsExactly("tax")
        // The old type batch is gone: nothing is called `score:type`.
        assertThat(r.interpreter.transcript.none { it.name == "score:type" }).isTrue()
    }

    @Test
    fun `a family below the threshold is the abstain family and asks the core slots only`() {
        val r = run(profile = ScoringProfile(thresholds = mapOf(ScoringProfile.FAMILY to 0.0))) { -5.0 }
        assertThat(r.result.documentType).isEqualTo(ExtractionSchema.FREE_FORM)
        // free_form is never scored, whatever the model says.
        assertThat(r.session.scored.flatten().none { it.contains(ExtractionSchema.FREE_FORM.description) }).isTrue()
        val asked = r.interpreter.transcript.map { it.name }.filter { it.startsWith("score:slot:") }.map { it.removePrefix("score:slot:") }
        assertThat(asked).containsNoneOf("fee", "original_due_date")
    }

    @Test
    fun `an invoice the classifier filed as an official letter is still asked for its reference numbers`() {
        val r = run(scorer = answers(family("official_letter")))
        assertThat(r.result.documentType).isEqualTo(ExtractionSchema.OFFICIAL_LETTER)
        val asked = r.interpreter.transcript.map { it.name }.filter { it.startsWith("score:slot:") }.map { it.removePrefix("score:slot:") }.toSet()
        assertThat(asked).containsAtLeast("invoice_no", "customer_no", "total", "due_date", "iban")
        // The family-specific ones stay with their family.
        assertThat(asked).containsNoneOf("fee", "original_due_date")
    }

    @Test
    fun `the slots are the family's and those of the best two topics`() {
        val r = run(scorer = answers(family("official_letter"), topic("tax"), topic("government"), topic("insurance")))
        val asked = r.interpreter.transcript.map { it.name }.filter { it.startsWith("score:slot:") }.map { it.removePrefix("score:slot:") }.toSet()
        // The family's own slots, then the slots of tax and government (the two best, in the registry's order for equal scores).
        assertThat(asked).containsAtLeast("objection_deadline", "tax_no", "case_no")
        // The third topic adds nothing: previous_amount belongs to insurance only.
        assertThat(asked).doesNotContain("previous_amount")
    }

    @Test
    fun `a family a person chose is read as it, skipping the family scores`() {
        val r = run(forcedFamily = "receipt", scorer = answers(family("invoice_bill"), topic("shopping")))
        assertThat(r.result.documentType).isEqualTo(ExtractionSchema.RECEIPT)
        assertThat(r.session.scored.flatten().none { it.contains("Is this document ") }).isTrue()
        assertThat(r.result.topics).containsExactly("shopping")
        // The family's slots are asked: a receipt has its receipt number, an invoice would not.
        assertThat(r.interpreter.transcript.map { it.name }).contains("score:family")
    }

    @Test
    fun `a family id the schema does not know is not forced`() {
        val r = run(forcedFamily = "spaceship", scorer = answers(family("invoice_bill")))
        assertThat(r.result.documentType).isEqualTo(ExtractionSchema.INVOICE_BILL)
    }

    @Test
    fun `with the topics left to the second stage the first scores none and the second stores them`() {
        val first = run(topicsInFirstStage = false, stages = ExtractionV2Pipeline.Stages.FIRST, scorer = answers(family("invoice_bill"), topic("tax")))
        assertThat(first.session.scored.flatten().none { it.contains("Does this document concern") }).isTrue()
        assertThat(first.result.topics).isEmpty()
        val incoming = ExtractionSchema.DEFAULT.familiesFor(com.postsaimanager.core.domain.extraction.v2.DocDirection.INCOMING).size
        assertThat(first.interpreter.transcript.single { it.name == "score:family" }.answer!!.split(',')).hasSize(incoming)

        val all = run(topicsInFirstStage = false, scorer = answers(family("invoice_bill"), topic("tax")))
        assertThat(all.result.topics).containsExactly("tax")
        // The topics were scored once, in the second stage, still in the body session (before the writing session opened).
        assertThat(all.interpreter.transcript.count { it.name == "score:family" }).isEqualTo(2)
    }

    // ── the structured address ──

    @Test
    fun `a letter with a recipient block gets the addressee's and the sender's structured address`() {
        val r = run(scorer = answers(family("invoice_bill"), "the name of a private person"))
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
    fun `a family with no recipient block reads no address`() {
        val r = run(scorer = answers(family("receipt")))
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
