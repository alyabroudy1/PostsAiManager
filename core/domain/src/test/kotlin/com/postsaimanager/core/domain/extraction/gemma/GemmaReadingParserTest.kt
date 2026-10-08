package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GemmaReadingParserTest {

    private val vocab = GemmaVocabulary.DEFAULT

    private fun ok(text: String) = (GemmaReadingParser.parse(text) as GemmaReadingParser.Parsed.Ok).reading

    @Test
    @DisplayName("asksReader is read as yes, no or not given, and the event kind as the registry's id")
    fun `asks reader and event kind`() {
        assertThat(ok(answer(mapOf("asksReader" to str("yes")))).asksReader).isTrue()
        assertThat(ok(answer(mapOf("asksReader" to str("NO")))).asksReader).isFalse()
        assertThat(ok(answer(mapOf("asksReader" to str("maybe")))).asksReader).isNull()
        assertThat(ok("{}").asksReader).isNull()
        assertThat(ok(answer(mapOf("eventKind" to str("approval")))).eventKind).isEqualTo("approval")
    }

    @Test
    @DisplayName("paid is read as one of the three states, or not given")
    fun `paid`() {
        assertThat(ok(answer(mapOf("paid" to str("already_paid")))).paid).isEqualTo(PaidState.ALREADY_PAID)
        assertThat(ok(answer(mapOf("paid" to str("to_pay")))).paid).isEqualTo(PaidState.TO_PAY)
        assertThat(ok(answer(mapOf("paid" to str("not_applicable")))).paid).isEqualTo(PaidState.NOT_APPLICABLE)
        assertThat(ok(answer(mapOf("paid" to str("perhaps")))).paid).isNull()
        assertThat(ok("{}").paid).isNull()
    }

    @Test
    @DisplayName("a full answer is read into its fields: the one-letter keys and the codes map back to the registries' ids")
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
            ),
        )

        val r = ok(json)

        assertThat(r.sender).isEqualTo(GemmaParty(id = "M1", kind = "company"))
        assertThat(r.addressee).isEqualTo(GemmaParty(id = "L2", kind = "person"))
        assertThat(r.contact).isNull()
        assertThat(r.dates.map { it.candidateId to it.meaning }).containsExactly("D1" to "LETTER_DATE", "D2" to "DUE_DATE").inOrder()
        assertThat(r.amounts.single().candidateId).isEqualTo("A1")
        assertThat(r.amounts.single().meaning).isEqualTo("TOTAL_DUE")
        assertThat(r.references.single().meaning).isEqualTo("iban")
        assertThat(r.actions.single()).isEqualTo(GemmaAction("pay", "D2", "A1"))
        assertThat(r.category).isEqualTo("bill")
        assertThat(r.language).isEqualTo("de")
        assertThat(r.name).isEqualTo("Mahnung")
    }

    @Test
    @DisplayName("the answer is written in codes and short keys, and the codes are the registries' own order")
    fun `compact answer`() {
        val json = """{"a":"yes","p":"to_pay","c":"${vocab.categoryCodes.codeOf("bill")}","r":[{"w":"w1","i":"M1","k":"t3"}],""" +
            """"d":[{"i":"D2","m":"${vocab.dateMeaningCodes.codeOf("DUE_DATE")}"}],"m":[{"i":"A1","m":"${vocab.amountMeaningCodes.codeOf("TOTAL_DUE")}"}],""" +
            """"f":[],"k":[{"k":"${vocab.actionKindCodes.codeOf("pay")}","d":"D2","a":"A1"}],"e":"${vocab.eventKindCodes.codeOf("information")}","l":"de","n":"Rechnung"}"""

        val r = ok(json)

        assertThat(r.sender?.id).isEqualTo("M1")
        assertThat(r.sender?.kind).isEqualTo("company")
        assertThat(r.category).isEqualTo("bill")
        assertThat(r.dates.single().meaning).isEqualTo("DUE_DATE")
        assertThat(r.amounts.single().meaning).isEqualTo("TOTAL_DUE")
        assertThat(r.actions.single().kind).isEqualTo("pay")
        assertThat(r.eventKind).isEqualTo("information")
        // Short: the whole answer of a typical letter is a few hundred characters (about 100 tokens), not the 1000 the long form took.
        assertThat(json.length).isLessThan(400)
    }

    @Test
    @DisplayName("the code of 'none of these' maps back to the word other, so the verifier reads it as no meaning")
    fun `none of these`() {
        val none = vocab.dateMeaningCodes.codeOf(GemmaVocabulary.OTHER)

        val r = ok("""{"d":[{"i":"D1","m":"$none"}]}""")

        assertThat(r.dates.single().meaning).isEqualTo("other")
    }

    @Test
    @DisplayName("a party listed twice for one role is one party (the first), and an entry with no role is no party")
    fun `parties`() {
        val r = ok("""{"r":[{"w":"w1","i":"M1","k":"t3"},{"w":"w1","i":"M2","k":"t1"},{"i":"M3"},{"w":"w2","i":"M2","k":"t1"}]}""")

        assertThat(r.sender?.id).isEqualTo("M1")
        assertThat(r.addressee?.id).isEqualTo("M2")
        assertThat(r.contact).isNull()
    }

    @Test
    @DisplayName("a picture-only answer names parties by text and carries the summary and the key facts (the second step has no text to write from)")
    fun `picture only parties`() {
        val r = ok(
            """{"r":[{"w":"w1","n":"شركة الكهرباء","k":"t3"}],"d":[{"v":"2026-10-01","m":"${vocab.dateMeaningCodes.codeOf("DUE_DATE")}"}],""" +
                """"s":"Zahlung bis 1.10.","y":[{"l":"Kundennummer","v":"4402917"}]}""",
        )

        assertThat(r.sender?.text).isEqualTo("شركة الكهرباء")
        assertThat(r.dates.single().value).isEqualTo("2026-10-01")
        assertThat(r.summary).isEqualTo("Zahlung bis 1.10.")
        assertThat(r.keyInfo.single()).isEqualTo(GemmaFact("Kundennummer", "4402917"))
    }

    @Test
    @DisplayName("an id written in full is read as that id, so either spelling is understood")
    fun `ids in full`() {
        val r = ok("""{"c":"receipt","d":[{"i":"D1","m":"DUE_DATE"}]}""")

        assertThat(r.category).isEqualTo("receipt")
        assertThat(r.dates.single().meaning).isEqualTo("DUE_DATE")
    }

    @Test
    @DisplayName("missing or mistyped members stay empty, they do not fail the reading")
    fun `lenient`() {
        val r = ok("""{"r":"M1","d":"none","k":[{"nokind":1}],"y":[{"l":"x"}]}""")

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
