package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.SamplingPurpose
import com.postsaimanager.core.domain.ai.samplingFor
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeChatEngine
import com.postsaimanager.core.testing.FakeGemmaReaderStyle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The "Questions" reader over the chat engine (the question flow) and the switch that chooses it. */
class QuestionAnswerGemmaReaderTest {

    private val engine = FakeChatEngine().apply {
        structuredAnswer = "SENDER: Nordlicht Mobilfunk GmbH | company\nTYPE: bill"
        leadAnswer = "A reminder to pay a phone bill."
    }
    private val provider = FakeActiveModelProvider(path = "/models/gemma.litertlm", runtime = ModelRuntime.LITERT_LM, supportsImages = true)
    private val letter = MiniLetter().letter

    private fun reader() = QuestionAnswerGemmaReader(engine, provider)

    @Test
    @DisplayName("one conversation: the letter as plain lines with the picture and the summary question, then every question once in one free-text message")
    fun `the question flow`() = runBlocking<Unit> {
        val heard = mutableListOf<String>()

        val outcome = reader().read(GemmaReaderRequest(letter, listOf("/p1.png"), onSummary = { heard += it }, keepOpenAs = "doc-1")) as GemmaReaderOutcome.Stated

        val request = engine.structuredRequests.single()
        assertThat(request.schema).isEmpty()
        assertThat(request.imagePaths).containsExactly("/p1.png")
        assertThat(request.leadPrompt).contains(letter.lines.first().text)
        assertThat(request.leadPrompt).contains("summary")
        assertThat(request.leadPrompt).doesNotContain("L1 ")
        QaLabel.entries.forEach { assertThat(request.prompt).contains("${it.name}:") }
        assertThat(request.prompt).contains("DUE_DATE")
        assertThat(request.prompt).contains("pay (")
        assertThat(request.keepOpenAs).isEqualTo("doc-1")
        assertThat(heard).containsExactly("A reminder to pay a phone bill.")
        assertThat(outcome.text).startsWith("SENDER:")
        assertThat(outcome.notes.single()).contains("answered=2")
        assertThat(engine.loads.single().first).isEqualTo("/models/gemma.litertlm")
    }

    @Test
    @DisplayName("the chat's own sampling (free text), not greedy; with no summary wanted the letter and the questions are one message")
    fun `sampling and a single message`() = runBlocking<Unit> {
        reader().read(GemmaReaderRequest(letter, emptyList()))

        val request = engine.structuredRequests.single()
        val free = samplingFor(SamplingPurpose.FREE_TEXT)
        assertThat(request.topK).isEqualTo(free.topK)
        assertThat(request.temperature).isEqualTo(free.temperature)
        assertThat(request.leadPrompt).isNull()
        assertThat(request.prompt).contains(letter.lines.first().text)
        assertThat(request.prompt).contains("SENDER:")
    }

    @Test
    @DisplayName("unavailable, with the reason, when no answer comes or the letter has no text")
    fun `unavailable`() = runBlocking<Unit> {
        engine.structuredAnswer = null
        assertThat(reader().read(GemmaReaderRequest(letter, emptyList()))).isInstanceOf(GemmaReaderOutcome.Unavailable::class.java)
        assertThat(reader().read(GemmaReaderRequest(GemmaLetter(emptyList(), emptyList()), listOf("/p1.png")))).isInstanceOf(GemmaReaderOutcome.Unavailable::class.java)
    }

    @Test
    @DisplayName("the switch: JSON (the default) asks for a schema, Questions asks free text, a picture-only letter is always the JSON reader's")
    fun `the switch`() = runBlocking<Unit> {
        val style = FakeGemmaReaderStyle()
        val switched = StyleSwitchedGemmaReader(ChatEngineGemmaReader(engine, provider), QuestionAnswerGemmaReader(engine, provider), style)

        switched.read(GemmaReaderRequest(letter, emptyList()))
        style.set(ReaderStyle.QUESTIONS)
        switched.read(GemmaReaderRequest(letter, emptyList()))
        switched.read(GemmaReaderRequest(GemmaLetter(emptyList(), emptyList()), listOf("/p1.png")))

        assertThat(engine.structuredRequests.map { it.schema.isBlank() }).containsExactly(false, true, false).inOrder()
    }
}
