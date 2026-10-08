package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.actions.ActionKinds
import com.postsaimanager.core.domain.extraction.v2.DocCategory
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The schema is built per letter: the enums of the ids are the ids this letter has, the words come from the registries. */
class GemmaSchemaTest {

    private val mini = MiniLetter()
    private val schema: JsonObject = Json.parseToJsonElement(GemmaSchema.build(mini.letter)).jsonObject

    private fun property(name: String) = schema["properties"]!!.jsonObject[name]!!.jsonObject

    private fun enumOf(o: JsonObject): List<String> = o["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun itemEnum(list: String, field: String): List<String> =
        enumOf(property(list)["items"]!!.jsonObject["properties"]!!.jsonObject[field]!!.jsonObject)

    @Test
    @DisplayName("every field is required and nothing else may be added")
    fun `all fields required`() {
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertThat(required).containsExactly(
            "asksReader", "category", "sender", "addressee", "contact", "subjectPerson", "dates", "amounts", "references", "actions",
            "eventKind", "language", "name", "summary", "keyInfo",
        )
        assertThat(schema["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    @DisplayName("asksReader is decided first and the category right after it, so the category is informed by it (the order is the model's order of writing)")
    fun `asks reader first then category`() {
        val order = schema["properties"]!!.jsonObject.keys.toList()
        assertThat(order.take(2)).containsExactly("asksReader", "category").inOrder()
        assertThat(schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.take(2)).containsExactly("asksReader", "category").inOrder()
        assertThat(enumOf(property("asksReader"))).containsExactly("no", "yes")
    }

    @Test
    @DisplayName("the picture-only schema asks the same two questions first")
    fun `image only asks reader first`() {
        val s = Json.parseToJsonElement(GemmaSchema.build(GemmaLetter(emptyList(), emptyList()))).jsonObject
        assertThat(s["properties"]!!.jsonObject.keys.take(2)).containsExactly("asksReader", "category").inOrder()
    }

    @Test
    @DisplayName("the event kind is one of the timeline registry's scored kinds, or information")
    fun `event kind`() {
        assertThat(enumOf(property("eventKind")))
            .containsExactlyElementsIn(com.postsaimanager.core.domain.timeline.EventKinds.DEFAULT.scored.map { it.id } + "information")
    }

    @Test
    @DisplayName("a party is one of this letter's name candidates or lines, or none")
    fun `party ids are the letter's`() {
        val ids = enumOf(property("sender")["properties"]!!.jsonObject["id"]!!.jsonObject)
        assertThat(ids).containsAtLeast("M1", "M2", "M3", "L1", "L9", "none")
        assertThat(ids).doesNotContain("D1")
        assertThat(ids).doesNotContain("A1")
        // The four parties are offered the same ids.
        assertThat(enumOf(property("contact")["properties"]!!.jsonObject["id"]!!.jsonObject)).isEqualTo(ids)
    }

    @Test
    @DisplayName("dates and amounts are only the letter's date and amount candidates, their meanings the registry's plus other")
    fun `dates and amounts`() {
        assertThat(itemEnum("dates", "candidateId")).containsExactly("D1", "D2", "D3", "D4", "D5")
        assertThat(itemEnum("amounts", "candidateId")).containsExactly("A1", "A2", "A3")
        assertThat(itemEnum("dates", "meaning"))
            .containsExactlyElementsIn(ValueMeanings.DEFAULT.of(MeaningKind.DATE).map { it.id } + "other")
        assertThat(itemEnum("amounts", "meaning"))
            .containsExactlyElementsIn(ValueMeanings.DEFAULT.of(MeaningKind.AMOUNT).map { it.id } + "other")
    }

    @Test
    @DisplayName("references point at reference numbers, accounts and contact values, with the schema's reference slots as kinds")
    fun `references`() {
        assertThat(itemEnum("references", "candidateId")).containsExactly("I1", "I2", "N1", "T1")
        val kinds = itemEnum("references", "kind")
        assertThat(kinds).containsAtLeast("invoice_no", "customer_no", "case_no", "iban", "other")
    }

    @Test
    @DisplayName("an action is a registry kind with a date and an amount of this letter or none")
    fun `actions`() {
        assertThat(itemEnum("actions", "kind")).containsExactlyElementsIn(ActionKinds.ALL.map { it.id })
        assertThat(itemEnum("actions", "dateId")).containsExactly("D1", "D2", "D3", "D4", "D5", "none")
        assertThat(itemEnum("actions", "amountId")).containsExactly("A1", "A2", "A3", "none")
    }

    @Test
    @DisplayName("the category is the registry's categories and document")
    fun `category`() {
        assertThat(enumOf(property("category"))).containsExactlyElementsIn(DocCategory.DEFAULT.map { it.id } + "document")
    }

    @Test
    @DisplayName("the texts are bounded: a name of at most 60 characters, key facts of at most 6 with a label of a few words")
    fun `texts are bounded`() {
        assertThat(property("name")["maxLength"]!!.jsonPrimitive.content).isEqualTo("60")
        assertThat(property("keyInfo")["maxItems"]!!.jsonPrimitive.content).isEqualTo("6")
        val keyInfo = property("keyInfo")["items"]!!.jsonObject["properties"]!!.jsonObject
        assertThat(keyInfo["label"]!!.jsonObject["maxLength"]!!.jsonPrimitive.content).isEqualTo("30")
    }

    @Test
    @DisplayName("a letter with no date candidate cannot answer a date: the list is empty by maxItems, never an empty enum")
    fun `no dates`() {
        val noDates = GemmaLetter(mini.letter.lines, mini.letter.candidates.filter { it.kind != com.postsaimanager.core.domain.extraction.candidates.CandidateKind.DATE })
        val s = Json.parseToJsonElement(GemmaSchema.build(noDates)).jsonObject["properties"]!!.jsonObject["dates"]!!.jsonObject

        assertThat(s["maxItems"]!!.jsonPrimitive.content).isEqualTo("0")
    }

    @Test
    @DisplayName("a letter with no lines (an unreadable page) gets the picture-only schema: free strings, the same fields")
    fun `image only`() {
        val s = Json.parseToJsonElement(GemmaSchema.build(GemmaLetter(emptyList(), emptyList()))).jsonObject
        val props = s["properties"]!!.jsonObject
        val dates = props["dates"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject

        assertThat(dates["value"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("string")
        assertThat(props["sender"]!!.jsonObject["properties"]!!.jsonObject.keys).containsExactly("name", "kind")
        assertThat(s["required"] as JsonArray).hasSize(15)
    }
}
