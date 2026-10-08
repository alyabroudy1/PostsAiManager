package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GemmaReadingParserTest {

    private fun ok(text: String) = (GemmaReadingParser.parse(text) as GemmaReadingParser.Parsed.Ok).reading

    @Test
    @DisplayName("asksReader is read as yes, no or not given, and the event kind as the model wrote it")
    fun `asks reader and event kind`() {
        assertThat(ok(answer(mapOf("asksReader" to str("yes")))).asksReader).isTrue()
        assertThat(ok(answer(mapOf("asksReader" to str("NO")))).asksReader).isFalse()
        assertThat(ok(answer(mapOf("asksReader" to str("maybe")))).asksReader).isNull()
        assertThat(ok("{}").asksReader).isNull()
        assertThat(ok(answer(mapOf("eventKind" to str("approval")))).eventKind).isEqualTo("approval")
    }

    @Test
    @DisplayName("a full answer is read into its fields")
    fun `full answer`() {
        val json = answer(
            mapOf(
                "sender" to party("M1", "company"),
                "addressee" to party("L2"),
                "dates" to arr(value("D1", "LETTER_DATE"), value("D2", "DUE_DATE")),
                "amounts" to arr(value("A1", "TOTAL_DUE")),
                "references" to arr(obj("candidateId" to str("I1"), "kind" to str("iban"))),
                "actions" to arr(obj("kind" to str("pay"), "dateId" to str("D2"), "amountId" to str("A1"))),
                "category" to str("bill"),
                "name" to str("Mahnung"),
                "summary" to str("Pay it."),
                "keyInfo" to arr(obj("label" to str("Kundennummer"), "value" to str("4402917"))),
            ),
        )

        val r = ok(json)

        assertThat(r.sender).isEqualTo(GemmaParty(id = "M1", kind = "company"))
        assertThat(r.addressee?.id).isEqualTo("L2")
        assertThat(r.dates.map { it.candidateId to it.meaning }).containsExactly("D1" to "LETTER_DATE", "D2" to "DUE_DATE").inOrder()
        assertThat(r.amounts.single().candidateId).isEqualTo("A1")
        assertThat(r.references.single().meaning).isEqualTo("iban")
        assertThat(r.actions.single()).isEqualTo(GemmaAction("pay", "D2", "A1"))
        assertThat(r.category).isEqualTo("bill")
        assertThat(r.language).isEqualTo("de")
        assertThat(r.name).isEqualTo("Mahnung")
        assertThat(r.keyInfo.single()).isEqualTo(GemmaFact("Kundennummer", "4402917"))
    }

    @Test
    @DisplayName("a picture-only answer names parties by text")
    fun `picture only parties`() {
        val r = ok("""{"sender":{"name":"شركة الكهرباء","kind":"company"},"dates":[{"value":"2026-10-01","meaning":"DUE_DATE"}]}""")

        assertThat(r.sender?.text).isEqualTo("شركة الكهرباء")
        assertThat(r.dates.single().value).isEqualTo("2026-10-01")
    }

    @Test
    @DisplayName("missing or mistyped members stay empty, they do not fail the reading")
    fun `lenient`() {
        val r = ok("""{"sender":"M1","dates":"none","actions":[{"nokind":1}],"keyInfo":[{"label":"x"}]}""")

        assertThat(r.sender).isNull()
        assertThat(r.dates).isEmpty()
        assertThat(r.actions).isEmpty()
        assertThat(r.keyInfo).isEmpty()
    }

    @Test
    @DisplayName("text that is no JSON object is a bad answer with a reason")
    fun `bad`() {
        assertThat(GemmaReadingParser.parse("sorry, I cannot")).isInstanceOf(GemmaReadingParser.Parsed.Bad::class.java)
        assertThat(GemmaReadingParser.parse("[1,2]")).isInstanceOf(GemmaReadingParser.Parsed.Bad::class.java)
    }
}
