package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.SamplingPurpose
import com.postsaimanager.core.domain.ai.samplingFor
import com.postsaimanager.core.domain.extraction.text.SummaryLimits
import com.postsaimanager.core.domain.extraction.text.SummaryFacts
import com.postsaimanager.core.domain.extraction.text.SummaryWriter
import com.postsaimanager.core.model.SummarySource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The second step of a Gemma reading: the summary and the key facts, written from the stored reading, checked by code. */
class GemmaTextWriterTest {

    private val mini = MiniLetter()

    private val facts = SummaryFacts(
        familyId = "invoice_bill", sender = "Stadtwerke Beispiel GmbH", amount = "64.98 EUR", dueDate = "09.10.2026", date = "25.09.2026",
    )

    private class ScriptedGenerator(private val answers: List<String?>) : GemmaTextGenerator {
        val prompts = mutableListOf<String>()
        val schemas = mutableListOf<String>()
        val budgets = mutableListOf<Int>()
        override suspend fun generate(system: String, prompt: String, schema: String, maxTokens: Int): String? {
            prompts += prompt
            budgets += maxTokens
            schemas += schema
            return answers.getOrNull(prompts.size - 1)
        }
    }

    private fun request(paid: PaidState? = PaidState.TO_PAY, known: List<String> = emptyList(), language: String? = "de") =
        GemmaTextRequest(mini.ocrText, facts, known, language, paid)

    private fun write(generator: GemmaTextGenerator, request: GemmaTextRequest = request()) = runBlocking { GemmaTextWriter(generator).write(request) }

    private fun answerJson(summary: String, vararg facts: Pair<String, String>) =
        """{"s":${JsonPrimitive(summary)},"k":[${facts.joinToString(",") { """{"l":"${it.first}","v":"${it.second}"}""" }}]}"""

    private val good = "Stadtwerke Beispiel GmbH erinnert an die Zahlung von 64,98 € bis zum 09.10.2026."

    @Test
    @DisplayName("a grounded summary and the key facts that are in the letter come back, with the template never used")
    fun `written`() {
        val generator = ScriptedGenerator(listOf(answerJson(good, "Telefon" to "0800 555 0199")))

        val out = write(generator) as GemmaTextOutcome.Written

        assertThat(out.summary.origin).isEqualTo(SummarySource.MODEL)
        assertThat(out.summary.text).isEqualTo(good)
        assertThat(out.keyInfo.map { it.label to it.value }).containsExactly("Telefon" to "0800 555 0199")
        assertThat(generator.prompts).hasSize(1)
    }

    @Test
    @DisplayName("a key fact that repeats a value the reading holds, or is not in the letter, is dropped; the summary is unaffected")
    fun `key facts are verified`() {
        val generator = ScriptedGenerator(
            listOf(answerJson(good, "Kundennummer" to "4402917", "Telefon" to "0800 555 0199", "Konto" to "DE99 0000 0000 0000 0000 00")),
        )

        val out = write(generator, request(known = listOf("4402917"))) as GemmaTextOutcome.Written

        assertThat(out.keyInfo.map { it.label }).containsExactly("Telefon")
        assertThat(out.notes.any { it.contains("2 key fact") }).isTrue()
    }

    @Test
    @DisplayName("a summary with a number the letter does not hold is asked once more, not to copy; a second rejection settles on the template")
    fun `rejected summaries`() {
        val invented = "Stadtwerke Beispiel GmbH verlangt 99,99 €."
        val generator = ScriptedGenerator(listOf(answerJson(invented, "Telefon" to "0800 555 0199"), answerJson(invented)))

        val out = write(generator) as GemmaTextOutcome.Written

        assertThat(generator.prompts).hasSize(SummaryWriter.MAX_ASKS)
        assertThat(generator.prompts.last()).contains("Do not copy")
        assertThat(generator.prompts.first()).doesNotContain("Do not copy")
        assertThat(out.summary.origin).isEqualTo(SummarySource.TEMPLATE)
        assertThat(out.summary.code).isEqualTo(SummaryWriter.TEMPLATE_CODE)
        // The key facts of the first answer stay: they were checked on their own.
        assertThat(out.keyInfo.map { it.label }).containsExactly("Telefon")
    }

    @Test
    @DisplayName("a second answer that is good replaces a rejected first one")
    fun `second try`() {
        val generator = ScriptedGenerator(listOf(answerJson("Stadtwerke Beispiel GmbH verlangt 99,99 €."), answerJson(good)))

        val out = write(generator) as GemmaTextOutcome.Written

        assertThat(out.summary.origin).isEqualTo(SummarySource.MODEL)
        assertThat(out.summary.text).isEqualTo(good)
    }

    @Test
    @DisplayName("no answer at all (the model is busy or failed) is unavailable, so nothing is stored and the step is asked again later")
    fun `unavailable`() {
        val out = write(ScriptedGenerator(listOf(null)))

        assertThat(out).isInstanceOf(GemmaTextOutcome.Unavailable::class.java)
    }

    @Test
    @DisplayName("an answer that is no JSON object counts as a failed attempt; two of them settle on the template")
    fun `unreadable answers`() {
        val generator = ScriptedGenerator(listOf("sorry", "still sorry"))

        val out = write(generator) as GemmaTextOutcome.Written

        assertThat(out.summary.origin).isEqualTo(SummarySource.TEMPLATE)
        assertThat(out.keyInfo).isEmpty()
    }

    @Test
    @DisplayName("the summary is told whether the document was paid already, and never to ask for a payment that was made")
    fun `paid context`() {
        val paid = ScriptedGenerator(listOf(answerJson(good)))
        write(paid, request(paid = PaidState.ALREADY_PAID))
        val unknown = ScriptedGenerator(listOf(answerJson(good)))
        write(unknown, request(paid = null))

        assertThat(paid.prompts.single()).contains("PAYMENT: ${PaidState.ALREADY_PAID.sentence}")
        assertThat(paid.prompts.single()).contains("never ask the reader to pay")
        assertThat(unknown.prompts.single()).doesNotContain("PAYMENT:")
        assertThat(unknown.prompts.single()).doesNotContain("never ask the reader to pay")
    }

    @Test
    @DisplayName("the prompt gives the letter's text, the verified facts and the language; the schema asks for one summary and a few facts")
    fun `prompt and schema`() {
        val generator = ScriptedGenerator(listOf(answerJson(good)))
        write(generator, request(language = "de"))

        val prompt = generator.prompts.single()
        assertThat(prompt).contains("Zahlungserinnerung zur Rechnung vom 19.08.2026")
        assertThat(prompt).contains("- sender: Stadtwerke Beispiel GmbH")
        assertThat(prompt).contains("- amount: 64.98 EUR")
        assertThat(prompt).contains("language with the code \"de\"")
        val schema = Json.parseToJsonElement(generator.schemas.single()).jsonObject
        assertThat(schema["properties"]!!.jsonObject.keys).containsExactly("s", "k").inOrder()
        assertThat(schema["required"].toString()).contains("\"s\"")
    }

    @Test
    @DisplayName("when the first turn wrote the summary, only the key facts are asked and no summary is stored from this step")
    fun `facts only`() {
        val generator = ScriptedGenerator(listOf("""{"k":[{"l":"Telefon","v":"0800 555 0199"}]}"""))

        val out = write(generator, request().let { GemmaTextRequest(it.ocrText, it.facts, it.knownValues, it.languageCode, it.paid, writeSummary = false) }) as GemmaTextOutcome.Written

        assertThat(out.summaryAsked).isFalse()
        assertThat(out.keyInfo.map { it.label to it.value }).containsExactly("Telefon" to "0800 555 0199")
        assertThat(generator.prompts.single()).doesNotContain("one or two sentences")
        val schema = Json.parseToJsonElement(generator.schemas.single()).jsonObject
        assertThat(schema["properties"]!!.jsonObject.keys).containsExactly("k")
        assertThat(schema["required"].toString()).doesNotContain("\"s\"")
    }

    @Test
    @DisplayName("the summary length is the one SummaryLimits value in the prompt and the schema, and the budget has room for it")
    fun `summary cap and token budget`() {
        val generator = ScriptedGenerator(listOf(answerJson(good)))

        write(generator)

        assertThat(generator.budgets.single()).isEqualTo(GemmaTextWriter.maxTokens(withSummary = true))
        assertThat(generator.prompts.single()).contains("at most ${SummaryLimits.MAX_CHARS} characters")
        val summary = Json.parseToJsonElement(generator.schemas.single()).jsonObject["properties"]!!.jsonObject["s"]!!.jsonObject
        assertThat(summary["maxLength"].toString()).isEqualTo(SummaryLimits.MAX_CHARS.toString())
    }

    @Test
    @DisplayName("the facts-only step has a tight schema (at most 6 facts, label 30, value 80) and a 400-token cap")
    fun `tight schema and cap`() {
        val generator = ScriptedGenerator(listOf("""{"k":[]}"""))

        write(generator, GemmaTextRequest(mini.ocrText, facts, emptyList(), "de", null, writeSummary = false))

        assertThat(generator.budgets.single()).isEqualTo(400)
        val k = Json.parseToJsonElement(generator.schemas.single()).jsonObject["properties"]!!.jsonObject["k"]!!.jsonObject
        assertThat(k["maxItems"].toString()).isEqualTo("6")
        val props = k["items"]!!.jsonObject["properties"]!!.jsonObject
        assertThat(props["l"]!!.jsonObject["maxLength"].toString()).isEqualTo("30")
        assertThat(props["v"]!!.jsonObject["maxLength"].toString()).isEqualTo("80")
    }

    @Test
    @DisplayName("an answer cut off at the token cap keeps the complete facts before the cut and drops the half-written one")
    fun `cut off answer is salvaged`() {
        val cut = """{"k":[{"l":"Telefon","v":"0800 555 0199"},{"l":"Zeiten","v":"Mo bis Fr, 8 bis 12 Uhr, Mo bis Fr, 8 bis 12 Uhr, Mo bis Fr, 8 bis 12 Uhr, Mo bis"""
        val generator = ScriptedGenerator(listOf(cut))

        val out = write(generator, GemmaTextRequest(mini.ocrText, facts, emptyList(), "de", null, writeSummary = false)) as GemmaTextOutcome.Written

        assertThat(out.keyInfo.map { it.label to it.value }).containsExactly("Telefon" to "0800 555 0199")
        assertThat(out.notes.any { it.contains("cut off") }).isTrue()
    }

    @Test
    @DisplayName("a cut-off answer with no complete fact is unreadable, as before")
    fun `nothing to salvage`() {
        assertThat(PartialAnswer.salvage("""{"k":[{"l":"Telefon","v":"080""")).isNull()
        assertThat(PartialAnswer.salvage("no json")).isNull()
    }

    @Test
    @DisplayName("the text step is sampled as free text (the normal sampler), not greedy: greedy looped to the cap on two letters")
    fun `text step is free text sampled`() {
        val request = gemmaTextRequest("s", "p", "{}", 400)
        val normal = samplingFor(SamplingPurpose.FREE_TEXT)

        assertThat(request.topK).isEqualTo(normal.topK)
        assertThat(request.temperature).isEqualTo(normal.temperature)
        assertThat(request.topP).isEqualTo(normal.topP)
        assertThat(request.topK).isGreaterThan(1)
    }
}
