package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The grammar as a language: it accepts every answer of the benchmark oracle (which answers what
 * the manifest says, through the real ids), and it rejects what the id sets exist to keep out.
 * Checked with [GbnfMatcher], a small GBNF interpreter, so the grammar's speed-motivated shape
 * (unbounded lists and strings, shared rules) is proven equivalent in what it allows.
 */
class GrammarAcceptanceTest {

    private val schema = ExtractionSchema.DEFAULT

    private class Case(val letter: Letter, val prepared: Prepared, val matcher: GbnfMatcher, val oracle: String)

    private val cases: List<Case> by lazy {
        Letters.all.map { letter ->
            val prepared = Prepared(letter.pages)
            Case(letter, prepared, GbnfMatcher(StructuredGrammar.build(prepared.offered, schema)), Oracle.structured(letter, prepared).json)
        }
    }

    private fun withSlot(oracle: String, slot: String, value: JsonObject): String {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(oracle).jsonObject
        val slots = JsonObject(root.getValue("s").jsonObject + (slot to value))
        return JsonObject(root + ("s" to slots)).toString()
    }

    @Nested
    inner class Accepts {
        @Test
        fun `every oracle answer of the benchmark letters`() {
            for (c in cases) assertThat(c.matcher.accepts(c.oracle)).isTrue()
        }

        @Test
        fun `the same answers written with the optional spaces`() {
            for (c in cases) {
                val spaced = c.oracle.replace("\":", "\": ").replace("},{", "}, {")
                assertThat(c.matcher.accepts(spaced)).isTrue()
            }
        }

        @Test
        fun `lists and strings longer than the old counted limits`() {
            val c = cases.first { it.letter.id == Letters.invoice.id }
            val root = kotlinx.serialization.json.Json.parseToJsonElement(c.oracle).jsonObject
            val party = (root.getValue("parties") as JsonArray).first()
            val extra = buildJsonObject {
                put("lb", "L".repeat(80))
                put("k", "meter_number")
                put("id", "NONE")
                put("v", "v".repeat(300))
                put("c", "LOW")
            }
            val long = JsonObject(
                root + ("parties" to JsonArray(List(9) { party })) + ("x" to JsonArray(List(9) { extra })),
            ).toString()
            assertThat(c.matcher.accepts(long)).isTrue()
        }

        @Test
        fun `the text grammar accepts the oracle's free text`() {
            val matcher = GbnfMatcher(TextGrammar.build())
            // The grammar wants a summary of at least one character; a letter without one gets a placeholder.
            for (letter in Letters.all) assertThat(matcher.accepts(Oracle.text(letter).replace("\"summary\":\"\"", "\"summary\":\"s\""))).isTrue()
            assertThat(matcher.accepts(Oracle.text(Letters.invoice).replace("\"summary\":", "\"summary_\":"))).isFalse()
        }
    }

    @Nested
    inner class Rejects {
        private val c get() = cases.first { it.letter.id == Letters.invoice.id }

        @Test
        fun `an id that was never offered, in every kind of slot`() {
            assertThat(c.matcher.accepts(withSlot(c.oracle, "total", idValue("A999", "TOTAL_DUE")))).isFalse()
            assertThat(c.matcher.accepts(withSlot(c.oracle, "letter_date", idValue("D999", "LETTER_DATE")))).isFalse()
            assertThat(c.matcher.accepts(withSlot(c.oracle, "iban", buildJsonObject { put("id", "I999"); put("c", "HIGH") }))).isFalse()
            assertThat(c.matcher.accepts(withSlot(c.oracle, "reference", buildJsonObject { put("id", "N999"); put("c", "HIGH") }))).isFalse()
        }

        @Test
        fun `an id of another kind`() {
            val o = c.prepared.offered
            val date = o.idsOf(CandidateKind.DATE).first()
            val amount = o.idsOf(CandidateKind.AMOUNT).first()
            val iban = o.idsOf(CandidateKind.IBAN).first()
            assertThat(c.matcher.accepts(withSlot(c.oracle, "total", idValue(date, "TOTAL_DUE")))).isFalse()
            assertThat(c.matcher.accepts(withSlot(c.oracle, "letter_date", idValue(amount, "LETTER_DATE")))).isFalse()
            assertThat(c.matcher.accepts(withSlot(c.oracle, "total", idValue(iban, "TOTAL_DUE")))).isFalse()
        }

        @Test
        fun `a role outside the enum, a confidence outside the words, a slot the type does not have`() {
            assertThat(c.matcher.accepts(withSlot(c.oracle, "total", idValue(c.prepared.offered.idsOf(CandidateKind.AMOUNT).first(), "MAYBE")))).isFalse()
            val badConfidence = c.oracle.replace("\"HIGH\"", "\"CERTAIN\"")
            assertThat(c.matcher.accepts(badConfidence)).isFalse()
            val foreign = withSlot(c.oracle, "appointment", idValue(c.prepared.offered.idsOf(CandidateKind.DATE).first(), "APPOINTMENT"))
            assertThat(c.matcher.accepts(foreign)).isFalse()
        }

        @Test
        fun `a type outside the schema and an extra that points at an unknown id`() {
            val unknownType = c.oracle.replaceFirst(Regex("\"type\":\"[a-z_]+\""), "\"type\":\"contract\"")
            assertThat(c.matcher.accepts(unknownType)).isFalse()
            val root = kotlinx.serialization.json.Json.parseToJsonElement(c.oracle).jsonObject
            val extra = buildJsonObject {
                put("lb", "x")
                put("k", "xx")
                put("id", "Z9")
                put("v", "")
                put("c", "LOW")
            }
            assertThat(c.matcher.accepts(JsonObject(root + ("x" to JsonArray(listOf(extra)))).toString())).isFalse()
        }

        @Test
        fun `a string with a line break or an unescaped quote`() {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(c.oracle).jsonObject
            fun extraWith(v: String) = JsonObject(
                root + (
                    "x" to JsonArray(
                        listOf(buildJsonObject { put("lb", "x"); put("k", "xx"); put("id", "NONE"); put("v", v); put("c", "LOW") }),
                    )
                    ),
            ).toString()
            assertThat(c.matcher.accepts(extraWith("fine"))).isTrue()
            // A raw line feed inside a string is not allowed (Json escapes it as \n, so break the text by hand).
            assertThat(c.matcher.accepts(extraWith("fine").replace("fine", "a\nb"))).isFalse()
        }

        @Test
        fun `a truncated answer`() {
            assertThat(c.matcher.accepts(c.oracle.dropLast(1))).isFalse()
            assertThat(c.matcher.accepts("")).isFalse()
        }

        private fun idValue(id: String, role: String): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("r", role)
            put("c", "HIGH")
        }
    }

    @Nested
    inner class Shape {
        private val grammar = StructuredGrammar.build(Prepared(Letters.invoice.pages).offered, schema)

        /** The grammar text with string literals and char classes blanked, so only its operators remain. */
        private fun operators(g: String): String {
            val literal = Regex("\"(\\\\.|[^\"\\\\])*\"")
            val charClass = Regex("\\[(\\\\.|[^\\]\\\\])*\\]")
            return charClass.replace(literal.replace(g, "\"\""), "[]")
        }

        @Test
        fun `no counted repetition anywhere, in either grammar`() {
            for (g in listOf(grammar, TextGrammar.build())) assertThat(operators(g)).doesNotContain("{")
        }

        @Test
        fun `the parts every type shares are written once`() {
            val lines = grammar.lines()
            for (name in listOf("head", "core", "tail")) assertThat(lines.count { it.startsWith("$name ::=") }).isEqualTo(1)
            for (t in schema.types) {
                val rule = lines.first { it.startsWith("t-" + t.id.replace('_', '-') + " ::=") }
                assertThat(rule).contains(" head core")
                assertThat(rule).endsWith(" tail")
            }
        }

        @Test
        fun `the grammar is smaller than the counted-repetition one it replaced`() {
            // The counted version measured 7,910 characters and 60 rules for the same letter.
            assertThat(grammar.length).isLessThan(6_000)
            assertThat(grammar.lines().size).isAtMost(60)
        }
    }
}
