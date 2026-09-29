package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The grammar no longer counts (unbounded lists and strings, for decoding speed), so the reading of
 * the answer enforces the caps: too many entries and too long text are cut, never trusted.
 */
class ParserTruncationTest {

    private fun call1(json: String) = (InterpretationParser.parse(json) as InterpretationParser.Parsed.Ok).value

    private fun party(i: Int) = """{"r":"ADDRESSEE","id":"M$i","k":"PERSON","rel":"NONE","c":"HIGH"}"""

    private fun extra(i: Int, label: String = "L$i", key: String = "k$i", value: String = "") =
        """{"lb":"$label","k":"$key","id":"NONE","v":"$value","c":"LOW"}"""

    @Test
    fun `parties beyond the cap are dropped, in order`() {
        val raw = call1("""{"type":"bill","parties":[${(1..10).joinToString(",") { party(it) }}],"s":{},"x":[]}""")
        assertThat(raw.parties).hasSize(StructuredGrammar.MAX_PARTIES)
        assertThat(raw.parties.map { it.id }).containsExactly("M1", "M2", "M3", "M4", "M5", "M6").inOrder()
    }

    @Test
    fun `extras beyond the cap are dropped, in order`() {
        val raw = call1("""{"type":"bill","parties":[],"s":{},"x":[${(1..9).joinToString(",") { extra(it) }}]}""")
        assertThat(raw.extras).hasSize(StructuredGrammar.MAX_EXTRAS)
        assertThat(raw.extras.first().label).isEqualTo("L1")
        assertThat(raw.extras.last().label).isEqualTo("L${StructuredGrammar.MAX_EXTRAS}")
    }

    @Test
    fun `cited references beyond the cap are dropped`() {
        val ids = (1..9).joinToString(",") { "\"N$it\"" }
        val raw = call1("""{"type":"outgoing_letter","parties":[],"s":{"cited_references":{"ids":[$ids],"c":"HIGH"}},"x":[]}""")
        assertThat(raw.slots.getValue("cited_references").ids).hasSize(StructuredGrammar.MAX_REF_IDS)
    }

    @Test
    fun `an extra's label, key and value are cut to their limits`() {
        val raw = call1(
            """{"type":"bill","parties":[],"s":{},"x":[${extra(1, "a".repeat(200), "k".repeat(90), "v".repeat(500))}]}""",
        )
        val x = raw.extras.single()
        assertThat(x.label).hasLength(StructuredGrammar.MAX_EXTRA_LABEL_CHARS)
        assertThat(x.key).hasLength(StructuredGrammar.MAX_EXTRA_KEY_CHARS)
        assertThat(x.value).hasLength(StructuredGrammar.MAX_EXTRA_VALUE_CHARS)
    }

    @Test
    fun `a quoted name and a deadline rule are cut to the quote limit`() {
        val long = "n".repeat(400)
        val raw = call1(
            """{"type":"bill","parties":[{"r":"SENDER","id":"$long","k":"COMPANY","rel":"NONE","c":"HIGH"}],""" +
                """"s":{"due_date":{"rule":"$long","r":"DEADLINE","c":"LOW"},"recipient_org":{"id":"$long","c":"LOW"}},"x":[]}""",
        )
        assertThat(raw.parties.single().id).hasLength(StructuredGrammar.MAX_QUOTE_CHARS)
        assertThat(raw.slots.getValue("due_date").rule).hasLength(StructuredGrammar.MAX_QUOTE_CHARS)
        assertThat(raw.slots.getValue("recipient_org").id).hasLength(StructuredGrammar.MAX_QUOTE_CHARS)
    }

    @Test
    fun `text within the limits is untouched`() {
        val raw = call1("""{"type":"bill","parties":[${party(1)}],"s":{},"x":[${extra(1, "Zählernummer", "meter_number", "12345")}]}""")
        assertThat(raw.extras.single().label).isEqualTo("Zählernummer")
        assertThat(raw.extras.single().value).isEqualTo("12345")
    }

    @Test
    fun `call 2 text fields and questions are cut to their limits`() {
        val text = """{"other":"${"o".repeat(90)}","title":"${"t".repeat(200)}","subject":"${"s".repeat(400)}",""" +
            """"summary":"${"m".repeat(900)}","qs":["${"q".repeat(300)}","b?","c?","d?","e?"]}"""
        val t = (InterpretationParser.parseText(text) as InterpretationParser.Parsed.Ok).value
        assertThat(t.otherLabel).hasLength(TextGrammar.MAX_OTHER_CHARS)
        assertThat(t.title).hasLength(TextGrammar.MAX_TITLE_CHARS)
        assertThat(t.subject).hasLength(TextGrammar.MAX_SUBJECT_CHARS)
        assertThat(t.summary).hasLength(TextGrammar.MAX_SUMMARY_CHARS)
        assertThat(t.questions).hasSize(TextGrammar.MAX_QUESTIONS)
        assertThat(t.questions.first()).hasLength(TextGrammar.MAX_QUESTION_CHARS)
    }
}
