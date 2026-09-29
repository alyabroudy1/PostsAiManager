package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.Validation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExtractionV2PipelineTest {

    private val pipeline = ExtractionV2Pipeline()

    private fun oracle(letter: Letter): ScriptedInterpreter {
        val p = Prepared(letter.pages)
        return ScriptedInterpreter(Oracle.structured(letter, p).json, Oracle.text(letter))
    }

    @Nested
    inner class TwoCalls {
        @Test
        fun `the structured call comes first, then the free text call with the type it chose`() = runTest {
            val model = oracle(Letters.n1)
            val r = pipeline.run(Letters.n1.pages, model, 4096)
            assertThat(model.interpretCalls).isEqualTo(1)
            assertThat(model.textCalls).isEqualTo(1)
            assertThat(model.lastTextRequest!!.documentTypeId).isEqualTo("reminder_dunning")
            assertThat(r.freeText.subject).isNotNull()
            assertThat(r.documentType).isEqualTo(ExtractionSchema.REMINDER_DUNNING)
        }

        @Test
        fun `when the free text call fails the structured reading stands`() = runTest {
            val p = Prepared(Letters.n1.pages)
            val model = ScriptedInterpreter(Oracle.structured(Letters.n1, p).json, text = null)
            val r = pipeline.run(Letters.n1.pages, model, 4096)
            assertThat(r.diagnostics.modelUsed).isTrue()
            assertThat(r.slots).isNotEmpty()
            assertThat(r.parties.sender).isNotNull()
            assertThat(r.freeText.subject).isNull()
            assertThat(r.freeText.title).isNull()
            assertThat(r.diagnostics.textError).isNotNull()
        }

        @Test
        fun `when the structured call fails there is no second call and only found values remain`() = runTest {
            val model = ScriptedInterpreter("""{"tc":"HI""", Oracle.text(Letters.n1))
            val r = pipeline.run(Letters.n1.pages, model, 4096)
            assertThat(model.textCalls).isEqualTo(0)
            assertThat(r.diagnostics.modelUsed).isFalse()
            assertThat(r.diagnostics.modelCalled).isTrue()
            assertThat(r.diagnostics.modelError).isNotNull()
            assertThat(r.documentType).isNull()
            assertThat(r.slots).isEmpty()
            assertThat(r.parties.all).isEmpty()
            assertThat(r.foundValues).isNotEmpty()
            assertThat(r.needsReview).isTrue()
        }

        @Test
        fun `the letter is budgeted against the window for each call`() = runTest {
            val big = (1..3).map { p ->
                (1..60).map { Din.b("Ein sehr langer Absatz mit viel Inhalt $p.$it und einem Betrag von 12,50 €", 0.117f, 0.05f + 0.012f * it) }
            }
            val small = oracle(Letters.n1).let { ScriptedInterpreter("""{"type":"other","tc":"LOW","lang":"de","parties":[],"s":{},"x":[]}""", "{}") }
            val r = pipeline.run(big, small, 2048)
            val budget = ExtractionV2Pipeline.budgetChars(2048, small.maxAnswerTokens, small.promptOverheadChars(Prepared(big).offered))
            assertThat(small.lastRequest!!.layoutText.length).isAtMost(budget)
            assertThat(r.diagnostics.layoutComplete).isFalse()
            assertThat(r.diagnostics.layoutCharsSent).isLessThan(r.diagnostics.layoutCharsTotal)
            assertThat(r.diagnostics.totalPages).isEqualTo(3)
            // call 2 has a smaller fixed prompt and a smaller answer, so it may read more of the letter
            assertThat(small.lastTextRequest!!.layoutText.length).isAtLeast(small.lastRequest!!.layoutText.length)
        }

        @Test
        fun `the characters per token estimate is the conservative 2_5`() {
            assertThat(ExtractionV2Pipeline.CHARS_PER_TOKEN).isEqualTo(2.5)
            assertThat(ExtractionV2Pipeline.budgetChars(4096, 640, 3000)).isEqualTo(((4096 - 640) * 2.5).toInt() - 3000)
            assertThat(ExtractionV2Pipeline.budgetChars(1000, 900, 5000)).isEqualTo(ExtractionV2Pipeline.MIN_LAYOUT_CHARS)
        }
    }

    @Nested
    inner class WithoutAModel {
        @Test
        fun `only shape-based found values, no type, no roles, no guesses`() = runTest {
            val r = pipeline.run(Letters.n6.pages, null, 4096)
            assertThat(r.diagnostics.modelCalled).isFalse()
            assertThat(r.diagnostics.modelUsed).isFalse()
            assertThat(r.documentType).isNull()
            assertThat(r.slots).isEmpty()
            assertThat(r.parties.all).isEmpty()
            assertThat(r.freeText.title).isNull()
            assertThat(r.needsReview).isTrue()
            val kinds = r.foundValues.map { it.kind }.toSet()
            assertThat(kinds).containsAtLeast(CandidateKind.DATE, CandidateKind.AMOUNT, CandidateKind.IBAN)
            assertThat(kinds).doesNotContain(CandidateKind.NAME)
        }

        @Test
        fun `found values are unique and never a failed check`() = runTest {
            val r = pipeline.run(Letters.n1.pages, null, 4096)
            val keys = r.foundValues.map { it.kind to it.normalized }
            assertThat(keys.toSet().size).isEqualTo(keys.size)
            assertThat(r.foundValues.none { it.validation is Validation.Invalid }).isTrue()
        }
    }

    @Nested
    inner class OtherLanguagesAndNoKeywords {
        @Test
        fun `an English letter is typed and assigned through the model path`() = runTest {
            val r = pipeline.run(Letters.english.pages, oracle(Letters.english), 4096)
            assertThat(r.documentType).isEqualTo(ExtractionSchema.REMINDER_DUNNING)
            assertThat(r.language).isEqualTo("en")
            assertThat(r.slots.getValue(Slots.TOTAL).normalized).isEqualTo("142.80 GBP")
            assertThat(r.parties.sender!!.name).isEqualTo("Northwind Utilities Ltd.")
        }

        @Test
        fun `an Arabic letter with a German summary line is typed and assigned through the model path`() = runTest {
            val r = pipeline.run(Letters.arabic.pages, oracle(Letters.arabic), 4096)
            assertThat(r.language).isEqualTo("ar")
            assertThat(r.slots.getValue(Slots.TOTAL).normalized).isEqualTo("450.00 EUR")
            assertThat(r.slots.getValue(Slots.DUE_DATE).normalized).startsWith("2026-10-20")
            assertThat(r.parties.sender!!.name).isEqualTo("Al-Mithal Services GmbH")
            assertThat(r.parties.addressees.single().name).isEqualTo("إيريكا موستيرمان")
            // the Arabic subject line is quoted and verified against the Arabic OCR text
            assertThat(r.freeText.subject!!.value).startsWith("الموضوع")
        }

        @Test
        fun `Arabic-Indic digits are read as numbers`() {
            val p = Prepared(Letters.arabic.pages)
            assertThat(p.find(CandidateKind.AMOUNT, "450.00 EUR")).isNotNull()
            assertThat(p.find(CandidateKind.DATE, "2026-09-28")).isNotNull()
        }

        /** No "Rechnung", no "Betreff", no "Kundennummer", no "fällig": nothing a keyword list could hook into. */
        private val keywordFree = listOf(
            Din.b("Stadtlicht Versorgung AG", 0.11f, 0.05f),
            Din.b("Stadtlicht Versorgung AG · Lichtweg 1 · 12345 Musterstadt", 0.114f, 0.157f, w = 0.28f),
            Din.b("Frau", 0.115f, 0.178f),
            Din.b("Ida Beispiel", 0.115f, 0.194f),
            Din.b("Lindenweg 4", 0.115f, 0.210f),
            Din.b("54321 Beispieldorf", 0.115f, 0.226f),
            Din.b("Wir bitten um 45,90 EUR zum 30.10.2026 auf DE89 3704 0044 0532 0130 00.", 0.117f, 0.40f),
            Din.b("Ihre Vorgangsnummer 9981-22.", 0.117f, 0.42f),
            Din.b("Stand 02.10.2026", 0.117f, 0.44f),
        )

        @Test
        fun `a German letter with no keyword at all still gets typed and assigned, by the model`() = runTest {
            val pages = listOf(keywordFree)
            val p = Prepared(pages)
            val amount = p.find(CandidateKind.AMOUNT, "45.90 EUR")!!
            val due = p.find(CandidateKind.DATE, "2026-10-30")!!
            val iban = p.find(CandidateKind.IBAN, "DE89370400440532013000")!!
            val ref = p.offered.rows.map { it.candidate }.firstOrNull { it.kind == CandidateKind.REFERENCE }
            // nothing labels these values, so no rule could have assigned them
            assertThat(amount.labelKind).isNull()
            assertThat(due.labelKind).isNull()

            val sender = p.findName("Stadtlicht Versorgung AG")!!
            val addressee = p.findName("Ida Beispiel")!!
            val refSlot = ref?.let { """"reference":{"id":"${it.id}","c":"MEDIUM"},""" }.orEmpty()
            val json = """{"type":"bill","tc":"MEDIUM","lang":"de",
                "parties":[{"r":"SENDER","id":"${sender.id}","k":"COMPANY","rel":"NONE","c":"HIGH"},
                           {"r":"ADDRESSEE","id":"${addressee.id}","k":"PERSON","rel":"NONE","c":"HIGH"}],
                "s":{"total":{"id":"${amount.id}","r":"TOTAL_DUE","c":"HIGH"},"due_date":{"id":"${due.id}","r":"DUE_DATE","c":"HIGH"},
                     $refSlot"iban":{"id":"${iban.id}","c":"HIGH"}},"x":[]}"""
            val r = pipeline.run(pages, ScriptedInterpreter(json, null), 4096)

            assertThat(r.documentType).isEqualTo(ExtractionSchema.BILL)
            assertThat(r.slots.getValue(Slots.TOTAL).normalized).isEqualTo("45.90 EUR")
            assertThat(r.slots.getValue(Slots.DUE_DATE).normalized).startsWith("2026-10-30")
            assertThat(r.slots.getValue(Slots.IBAN).validation.isValid).isTrue()
            assertThat(r.parties.sender!!.name).isEqualTo("Stadtlicht Versorgung AG")
            assertThat(r.parties.addressees.single().name).isEqualTo("Ida Beispiel")
        }

        @Test
        fun `the grammar offers an unlabelled amount and date all the same`() {
            val p = Prepared(listOf(keywordFree))
            val amount = p.find(CandidateKind.AMOUNT, "45.90 EUR")!!
            val due = p.find(CandidateKind.DATE, "2026-10-30")!!
            val g = StructuredGrammar.build(p.offered, ExtractionSchema.DEFAULT)
            assertThat(g).contains("\\\"${amount.id}\\\"")
            assertThat(g).contains("\\\"${due.id}\\\"")
        }
    }

    @Nested
    inner class Candidates {
        @Test
        fun `identical values are offered once, with every place they were seen`() {
            val p = Prepared(Letters.n1.pages)
            val amounts = p.offered.rows.filter { it.candidate.kind == CandidateKind.AMOUNT && it.candidate.normalized == "64.98 EUR" }
            assertThat(amounts).hasSize(1)
            assertThat(amounts.single().nearLabels).isNotEmpty()
        }

        @Test
        fun `a kind over its cap is sampled across the pages and the cut is reported`() = runTest {
            val pages = (1..3).map { page ->
                (1..12).map { i -> Din.b("Posten $page.$i: ${page * 100 + i},00 €", 0.117f, 0.05f + 0.02f * i) }
            }
            val p = Prepared(pages)
            val amountPages = p.offered.rows.filter { it.candidate.kind == CandidateKind.AMOUNT }.flatMap { it.pages }.toSet()
            assertThat(amountPages).containsExactly(1, 2, 3)
            assertThat(p.offered.rows.count { it.candidate.kind == CandidateKind.AMOUNT }).isEqualTo(20)
            assertThat(p.offered.dropped[CandidateKind.AMOUNT]).isEqualTo(16)

            val model = ScriptedInterpreter("""{"type":"other","tc":"LOW","lang":"de","parties":[],"s":{},"x":[]}""", "{}")
            val r = pipeline.run(pages, model, 4096)
            assertThat(r.diagnostics.offeredDropped).containsEntry("AMOUNT", 16)
        }

        @Test
        fun `a time on its own is never offered as a date`() {
            val p = Prepared(listOf(listOf(Din.b("Öffnungszeit 08:00 Uhr bis 18:00 Uhr", 0.117f, 0.5f))))
            assertThat(p.offered.idsOf(CandidateKind.DATE, CandidateKind.DATETIME)).isEmpty()
        }
    }
}
