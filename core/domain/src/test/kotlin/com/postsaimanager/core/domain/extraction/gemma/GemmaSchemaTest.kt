package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.actions.ActionKinds
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.DocCategory
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings
import com.postsaimanager.core.domain.timeline.EventKinds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The schema is built per letter: the enums of the ids are the ids this letter has, the words are the registries' codes. */
class GemmaSchemaTest {

    private val vocab = GemmaVocabulary.DEFAULT
    private val mini = MiniLetter()
    private val schema: JsonObject = Json.parseToJsonElement(GemmaSchema.build(mini.letter)).jsonObject

    private fun property(name: String) = schema["properties"]!!.jsonObject[name]!!.jsonObject

    private fun enumOf(o: JsonObject): List<String> = o["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun itemProperty(list: String, field: String) = property(list)["items"]!!.jsonObject["properties"]!!.jsonObject[field]!!.jsonObject

    private fun itemEnum(list: String, field: String): List<String> = enumOf(itemProperty(list, field))

    private fun codesOf(book: CodeBook, ids: List<String>) = ids.map { book.codeOf(it)!! }

    @Test
    @DisplayName("every field is required, with one-letter keys, and nothing else may be added")
    fun `all fields required`() {
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertThat(required).containsExactly("a", "p", "c", "r", "d", "m", "f", "k", "e", "l", "n").inOrder()
        assertThat(schema["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    @DisplayName("the summary and the key facts are not part of the reading's answer: a second step writes them")
    fun `no summary and no key facts`() {
        assertThat(schema["properties"]!!.jsonObject.keys).containsNoneOf("s", "y", "summary", "keyInfo")
    }

    @Test
    @DisplayName("asksReader is decided first, then paid, then the category, so the category is informed by both (the order is the model's order of writing)")
    fun `asks reader then paid then category`() {
        val order = schema["properties"]!!.jsonObject.keys.toList()
        assertThat(order.take(3)).containsExactly("a", "p", "c").inOrder()
        assertThat(schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.take(3)).containsExactly("a", "p", "c").inOrder()
        assertThat(enumOf(property("a"))).containsExactly("no", "yes")
        assertThat(enumOf(property("p"))).containsExactly("already_paid", "to_pay", "not_applicable").inOrder()
    }

    @Test
    @DisplayName("the picture-only schema asks the same three questions first, and keeps the summary and the key facts (it has no text for a second step)")
    fun `image only asks reader first`() {
        val s = Json.parseToJsonElement(GemmaSchema.build(GemmaLetter(emptyList(), emptyList()))).jsonObject
        assertThat(s["properties"]!!.jsonObject.keys.take(3)).containsExactly("a", "p", "c").inOrder()
        assertThat(s["properties"]!!.jsonObject.keys).containsAtLeast("s", "y")
    }

    @Test
    @DisplayName("the event kind is one of the timeline registry's scored kinds, or information, as codes")
    fun `event kind`() {
        assertThat(enumOf(property("e")))
            .containsExactlyElementsIn(codesOf(vocab.eventKindCodes, EventKinds.DEFAULT.scored.map { it.id } + "information"))
    }

    @Test
    @DisplayName("the parties are a list of entries (who, a name candidate or a line of this letter, a kind), empty when nobody exists")
    fun `party ids are the letter's`() {
        assertThat(property("r")["maxItems"]!!.jsonPrimitive.content).isEqualTo("4")
        val ids = itemEnum("r", "i")
        assertThat(ids).containsAtLeast("M1", "M2", "M3", "L1", "L9", "none")
        assertThat(ids).doesNotContain("D1")
        assertThat(ids).doesNotContain("A1")
        assertThat(itemEnum("r", "w")).containsExactlyElementsIn(vocab.partyRoleCodes.codes)
        assertThat(itemEnum("r", "k")).containsExactlyElementsIn(vocab.partyKindCodes.codes)
    }

    @Test
    @DisplayName("dates and amounts are only the letter's date and amount candidates, their meanings the registry's plus other, as codes")
    fun `dates and amounts`() {
        assertThat(itemEnum("d", "i")).containsExactly("D1", "D2", "D3", "D4", "D5")
        assertThat(itemEnum("m", "i")).containsExactly("A1", "A2", "A3")
        assertThat(itemEnum("d", "m"))
            .containsExactlyElementsIn(codesOf(vocab.dateMeaningCodes, ValueMeanings.DEFAULT.of(MeaningKind.DATE).map { it.id } + "other"))
        assertThat(itemEnum("m", "m"))
            .containsExactlyElementsIn(codesOf(vocab.amountMeaningCodes, ValueMeanings.DEFAULT.of(MeaningKind.AMOUNT).map { it.id } + "other"))
    }

    @Test
    @DisplayName("references point at reference numbers, accounts and contact values, with the schema's reference slots as kind codes")
    fun `references`() {
        assertThat(itemEnum("f", "i")).containsExactly("I1", "I2", "N1", "T1")
        val kinds = itemEnum("f", "k")
        assertThat(kinds).containsAtLeastElementsIn(codesOf(vocab.referenceKindCodes, listOf("invoice_no", "customer_no", "case_no", "iban", "other")))
    }

    @Test
    @DisplayName("an action is a registry kind (as a code) with a date and an amount of this letter or none")
    fun `actions`() {
        assertThat(itemEnum("k", "k")).containsExactlyElementsIn(codesOf(vocab.actionKindCodes, ActionKinds.ALL.map { it.id }))
        assertThat(itemEnum("k", "d")).containsExactly("D1", "D2", "D3", "D4", "D5", "none")
        assertThat(itemEnum("k", "a")).containsExactly("A1", "A2", "A3", "none")
    }

    @Test
    @DisplayName("the category is the registry's categories and document, as codes")
    fun `category`() {
        assertThat(enumOf(property("c"))).containsExactlyElementsIn(codesOf(vocab.categoryCodes, DocCategory.DEFAULT.map { it.id } + "document"))
    }

    @Test
    @DisplayName("the lists are as short as the letter allows: dates and amounts at most 6, references at most 5, actions at most 3")
    fun `lists are short`() {
        assertThat(property("d")["maxItems"]!!.jsonPrimitive.content).isEqualTo("6")
        assertThat(property("m")["maxItems"]!!.jsonPrimitive.content).isEqualTo("6")
        assertThat(property("f")["maxItems"]!!.jsonPrimitive.content).isEqualTo("5")
        assertThat(property("k")["maxItems"]!!.jsonPrimitive.content).isEqualTo("3")
    }

    @Test
    @DisplayName("the name is bounded to 60 characters")
    fun `texts are bounded`() {
        assertThat(property("n")["maxLength"]!!.jsonPrimitive.content).isEqualTo("60")
    }

    @Test
    @DisplayName("the codes are shorter than the ids they stand for")
    fun `codes are short`() {
        assertThat(vocab.amountMeaningCodes.codeOf("INVOICE_TOTAL")).hasLength(2)
        assertThat(vocab.categoryCodes.codes.maxOf { it.length }).isAtMost(3)
        assertThat(vocab.eventKindCodes.codes.maxOf { it.length }).isAtMost(3)
    }

    @Test
    @DisplayName("the schema's size: far smaller than the answer's long form, and the answer a model writes for a typical letter is short")
    fun `a typical answer is short`() {
        val l = mini.letter
        val typical = answer(
            mapOf(
                "paid" to str("to_pay"),
                "sender" to party("M1", "company"), "addressee" to party("M2"),
                "dates" to arr(value("D1", "LETTER_DATE"), value("D2", "DUE_DATE")),
                "amounts" to arr(value("A1", "TOTAL_DUE")),
                "references" to arr(obj("candidateId" to str("N1"), "kind" to str("customer_no")), obj("candidateId" to str("I1"), "kind" to str("iban"))),
                "actions" to arr(obj("kind" to str("pay"), "dateId" to str("D2"), "amountId" to str("A1"))),
                "category" to str("bill"), "eventKind" to str("payment_reminder"), "name" to str("Zahlungserinnerung Rechnung"),
            ),
        )

        // About 3 characters a token for JSON: far under the 150 tokens the target asks for.
        assertThat(typical.length / 3).isLessThan(150)
        assertThat(l.lines).isNotEmpty()
    }

    @Test
    @DisplayName("a letter with no date candidate cannot answer a date: the list is empty by maxItems, never an empty enum")
    fun `no dates`() {
        val noDates = GemmaLetter(mini.letter.lines, mini.letter.candidates.filter { it.kind != CandidateKind.DATE })
        val s = Json.parseToJsonElement(GemmaSchema.build(noDates)).jsonObject["properties"]!!.jsonObject["d"]!!.jsonObject

        assertThat(s["maxItems"]!!.jsonPrimitive.content).isEqualTo("0")
    }

    @Test
    @DisplayName("a letter with no lines (an unreadable page) gets the picture-only schema: free strings, the same fields")
    fun `image only`() {
        val s = Json.parseToJsonElement(GemmaSchema.build(GemmaLetter(emptyList(), emptyList()))).jsonObject
        val props = s["properties"]!!.jsonObject
        val dates = props["d"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject

        assertThat(dates["v"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("string")
        assertThat(props["r"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject.keys).containsExactly("w", "n", "k").inOrder()
        assertThat(s["required"]!!.jsonArray).hasSize(13)
    }
}
