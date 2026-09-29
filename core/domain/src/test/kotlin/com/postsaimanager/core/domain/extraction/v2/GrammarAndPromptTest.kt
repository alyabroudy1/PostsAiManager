package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GrammarAndPromptTest {

    private val schema = ExtractionSchema.DEFAULT
    private val invoice = Prepared(Letters.invoice.pages)
    private val grammar = StructuredGrammar.build(invoice.offered, schema)

    private fun rules(g: String): Map<String, String> =
        g.lines().filter { it.isNotBlank() }.associate { line ->
            val (name, body) = line.split(" ::= ", limit = 2)
            name to body
        }

    /** The ids a grammar rule lists, whatever the surrounding syntax: `"\"A1\""` gives `A1`. */
    private fun idsIn(body: String): List<String> = Regex("\"\\\\\"([A-Z]{1,2}\\d+)\\\\\"\"").findAll(body).map { it.groupValues[1] }.toList()

    @Nested
    inner class Structured {

        @Test
        fun `there is one alternative per document type in the schema`() {
            val r = rules(grammar)
            val alternatives = r.getValue("root").split(" | ")
            assertThat(alternatives).hasSize(schema.types.size)
            for (t in schema.types) assertThat(r).containsKey("t-" + t.id.replace('_', '-'))
        }

        @Test
        fun `a type's rule has exactly its own slots`() {
            val r = rules(grammar)
            val health = r.getValue("t-health")
            for (s in ExtractionSchema.HEALTH.slots) assertThat(health).contains("\\\"${s.json}\\\":")
            assertThat(health).doesNotContain("\\\"invoice_no\\\":")
            assertThat(health).doesNotContain("\\\"policy_no\\\":")
            val bill = r.getValue("t-bill")
            assertThat(bill).contains("\\\"invoice_no\\\":")
            assertThat(bill).doesNotContain("\\\"appointment\\\":")
        }

        @Test
        fun `every type has the universal core`() {
            for (t in schema.types) for (core in Slots.CORE) assertThat(t.slots).contains(core)
        }

        @Test
        fun `each value rule lists exactly the offered ids of its kind, plus NONE`() {
            val r = rules(grammar)
            val o = invoice.offered
            assertThat(idsIn(r.getValue("amt")).toSet()).isEqualTo(o.idsOf(CandidateKind.AMOUNT).toSet())
            assertThat(idsIn(r.getValue("iban")).toSet()).isEqualTo(o.idsOf(CandidateKind.IBAN).toSet())
            assertThat(idsIn(r.getValue("ref")).toSet()).isEqualTo(o.idsOf(CandidateKind.REFERENCE).toSet())
            assertThat(idsIn(r.getValue("date")).toSet()).isEqualTo(o.idsOf(CandidateKind.DATE, CandidateKind.DATETIME).toSet())
            // A deadline is a date id or a quoted rule; no code-found "period" candidate exists any more.
            assertThat(idsIn(r.getValue("due")).toSet()).isEqualTo(o.idsOf(CandidateKind.DATE, CandidateKind.DATETIME).toSet())
            assertThat(r.getValue("due")).contains("\\\"rule\\\":")
            for (name in listOf("amt", "date", "due", "iban", "ref")) assertThat(r.getValue(name)).contains("\\\"NONE\\\"")
        }

        @Test
        fun `an amount id can never stand in a date slot, nor an IBAN in an amount slot`() {
            val r = rules(grammar)
            val amounts = invoice.offered.idsOf(CandidateKind.AMOUNT)
            val ibans = invoice.offered.idsOf(CandidateKind.IBAN)
            assertThat(amounts).isNotEmpty()
            assertThat(idsIn(r.getValue("date"))).containsNoneIn(amounts)
            assertThat(idsIn(r.getValue("amt"))).containsNoneIn(ibans)
        }

        @Test
        fun `a kind with no candidates leaves only NONE`() {
            val bare = Prepared(listOf(listOf(Din.b("Ein Text ohne Werte", 0.2f, 0.5f))))
            val r = rules(StructuredGrammar.build(bare.offered, schema))
            assertThat(r.getValue("amt")).isEqualTo("\"\\\"NONE\\\"\"")
            assertThat(r.getValue("iban")).isEqualTo("\"\\\"NONE\\\"\"")
            assertThat(r.getValue("refs")).isEqualTo("\"\\\"NONE\\\"\"")
        }

        @Test
        fun `every rule that is used is defined`() {
            val r = rules(grammar)
            val literal = Regex("\"(\\\\.|[^\"\\\\])*\"")
            val charClass = Regex("\\[[^\\]]*\\]")
            for ((name, body) in r) {
                val words = Regex("[a-z][a-z0-9-]*").findAll(charClass.replace(literal.replace(body, " "), " ")).map { it.value }
                for (w in words) assertThat(r).containsKey(w)
                assertThat(name).isNotEmpty()
            }
        }

        @Test
        fun `party roles, kinds, relations and confidence words are enums`() {
            val r = rules(grammar)
            for (role in PartyRole.entries) assertThat(r.getValue("prole")).contains("\\\"${role.name}\\\"")
            assertThat(r.getValue("conf")).isEqualTo("\"\\\"LOW\\\"\" | \"\\\"MEDIUM\\\"\" | \"\\\"HIGH\\\"\"")
            assertThat(r.getValue("party")).contains("\\\"c\\\":")
        }

        @Test
        fun `extras are bounded and may point at any offered candidate`() {
            val r = rules(grammar)
            assertThat(r.getValue("xlist")).contains("{0,${StructuredGrammar.MAX_EXTRAS - 1}}")
            val all = invoice.offered.rows.map { it.candidate.id }
            assertThat(idsIn(r.getValue("xid"))).containsExactlyElementsIn(all)
        }

        @Test
        fun `a name may be a candidate id or a quote`() {
            val r = rules(grammar)
            val names = invoice.offered.idsOf(CandidateKind.NAME)
            assertThat(names).isNotEmpty()
            // One neutral prefix for every name candidate.
            assertThat(names.all { it.startsWith("M") }).isTrue()
            assertThat(idsIn(r.getValue("nameref"))).containsExactlyElementsIn(names)
            assertThat(r.getValue("nameref")).endsWith("quote")
        }

        @Test
        fun `a quote cannot contain a quote mark or a line break`() {
            assertThat(rules(grammar).getValue("qchar")).isEqualTo("[^\"\\\\\\n\\r]")
        }
    }

    @Nested
    inner class Text {
        @Test
        fun `the text grammar has the five text keys and no candidate ids`() {
            val g = TextGrammar.build()
            for (k in listOf("other", "title", "subject", "summary", "qs")) assertThat(g).contains("\\\"$k\\\":")
            assertThat(idsIn(g)).isEmpty()
            assertThat(rules(g).getValue("qs")).contains("question")
        }
    }

    @Nested
    inner class Prompt {
        private val rulesOnly = SelectionPrompt.system(schema, withExample = false)

        @Test
        fun `the rules are written in English and describe roles by function, not by German cue words`() {
            for (cue in listOf("Frau", "Herr", "Sehr geehrte", "Aktenzeichen", "Ihr Zeichen", "i. A.", "z. Hd.", "Betreff", "Rechnung")) {
                assertThat(rulesOnly).doesNotContain(cue)
            }
            assertThat(rulesOnly).contains("who wrote and")
            assertThat(rulesOnly).contains("addressed to")
        }

        @Test
        fun `the example is one short German letter`() {
            val withExample = SelectionPrompt.system(schema, withExample = true)
            assertThat(withExample.length).isGreaterThan(rulesOnly.length)
            assertThat(withExample).contains("Grundschule Am Waldweg")
            assertThat(Regex("EXAMPLE").findAll(withExample).count()).isEqualTo(1)
        }

        @Test
        fun `the example is left out of a small context window`() {
            val small = ModelDocumentInterpreter(com.postsaimanager.core.testing.FakeAiEngine(), contextTokens = 2048)
            val big = ModelDocumentInterpreter(com.postsaimanager.core.testing.FakeAiEngine(), contextTokens = 4096)
            assertThat(small.promptOverheadChars(invoice.offered)).isLessThan(big.promptOverheadChars(invoice.offered))
        }

        @Test
        fun `the table lists each candidate with its id, text, near hint and page`() {
            val table = SelectionPrompt.table(invoice.offered)
            val total = invoice.find(CandidateKind.AMOUNT, "1284.50 EUR")!!
            val line = table.lines().first { it.startsWith(total.id + ":") }
            assertThat(line).contains("1.284,50")
            assertThat(line).contains("near:")
            assertThat(line).contains("p.2")
        }

        @Test
        fun `the user message is the letter then the candidates`() {
            val user = SelectionPrompt.user("=== PAGE 1 ===\n[body] x", invoice.offered)
            assertThat(user.indexOf("LETTER")).isLessThan(user.indexOf("CANDIDATES"))
            assertThat(user).contains("=== PAGE 1 ===")
        }

        @Test
        fun `the prompt lists every type of the schema it is given`() {
            val custom = ExtractionSchema(listOf(DocType.of("contract", com.postsaimanager.core.model.DocumentType.CONTRACT)))
            val prompt = SelectionPrompt.system(custom, withExample = false)
            assertThat(prompt).contains("(contract)")
            assertThat(prompt).doesNotContain("reminder_dunning")
        }

        @Test
        fun `the text prompt asks for the letter's own language`() {
            assertThat(SelectionPrompt.TEXT_SYSTEM).contains("letter's own language")
            assertThat(SelectionPrompt.textUser("=== PAGE 1 ===", "bill")).contains("DOCUMENT TYPE: bill")
        }
    }
}
