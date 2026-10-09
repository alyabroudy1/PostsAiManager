package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.SamplingPurpose
import com.postsaimanager.core.domain.ai.samplingFor
import com.postsaimanager.core.model.Accelerator
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
        structuredAnswer = "SUMMARY: A reminder to pay a phone bill.\nSENDER: Nordlicht Mobilfunk GmbH | company\nTYPE: bill"
    }
    private val provider = FakeActiveModelProvider(path = "/models/gemma.litertlm", runtime = ModelRuntime.LITERT_LM, supportsImages = true)
    private val style = FakeGemmaReaderStyle()

    /** The mini letter has a handful of lines: weak text, so the picture goes along. */
    private val weakLetter = MiniLetter().letter

    /** A page the OCR read well: plenty of lines and characters. */
    private val strongLetter = GemmaLetter(
        (1..30).map { GemmaLine("L$it", 1, "body", 0f, it / 40f, "A line of the letter with some words in it, number $it") },
        emptyList(),
    )

    private fun reader() = QuestionAnswerGemmaReader(engine, provider, style)

    @Test
    @DisplayName("one message: the letter as plain lines, the page picture, and every question once, the summary as the first line, no lead turn")
    fun `the question flow`() = runBlocking<Unit> {
        val heard = mutableListOf<String>()

        val outcome = reader().read(GemmaReaderRequest(weakLetter, listOf("/p1.png"), onSummary = { heard += it }, keepOpenAs = "doc-1")) as GemmaReaderOutcome.Stated

        val request = engine.structuredRequests.single()
        assertThat(request.schema).isEmpty()
        assertThat(request.leadPrompt).isNull()
        assertThat(request.imagePaths).containsExactly("/p1.png")
        assertThat(request.prompt).contains(weakLetter.lines.first().text)
        assertThat(request.prompt).doesNotContain("L1 ")
        QuestionPrompt.asked(withSummary = true).forEach { assertThat(request.prompt).contains("${it.name}:") }
        // The paid state is a line of its own (a word of it, such as to_pay, was taken for an action kind when it stood inside ASKS).
        assertThat(request.prompt).contains("${QaLabel.PAID}:")
        assertThat(request.prompt.indexOf("${QaLabel.SUMMARY}:")).isLessThan(request.prompt.indexOf("${QaLabel.SENDER}:"))
        assertThat(request.prompt.indexOf("${QaLabel.EVENT}:")).isLessThan(request.prompt.indexOf("${QaLabel.LANGUAGE}:"))
        assertThat(request.prompt).contains("DUE_DATE")
        assertThat(request.prompt).contains("pay (")
        assertThat(request.maxTokens).isAtMost(320)
        assertThat(request.keepOpenAs).isEqualTo("doc-1")
        assertThat(heard).containsExactly("A reminder to pay a phone bill.")
        assertThat(outcome.text).startsWith("SUMMARY:")
        assertThat(outcome.notes.first()).contains("answered=3")
        assertThat(engine.loads.single().first).isEqualTo("/models/gemma.litertlm")
    }

    @Test
    @DisplayName("the summary is handed on once, while the answer streams (before the call returns), and a summary only the final text holds is handed on at the end")
    fun `streamed summary`() = runBlocking<Unit> {
        val order = mutableListOf<String>()
        engine.structuredResponder = { order += "answer"; "SUMMARY: Short.\nTYPE: bill" }
        // The fake streams a line at a time before it returns, as the engine does.
        reader().read(GemmaReaderRequest(weakLetter, emptyList(), onSummary = { order += "summary:$it" }))
        assertThat(order).containsExactly("answer", "summary:Short.").inOrder()
        assertThat(engine.structuredRequests.single().onPartial).isNotNull()

        // No line break after the summary (it is the whole answer): the final text has it.
        engine.structuredResponder = { "SUMMARY: Only line" }
        val heard = mutableListOf<String>()
        reader().read(GemmaReaderRequest(weakLetter, emptyList(), onSummary = { heard += it }))
        assertThat(heard).containsExactly("Only line")

        // No summary wanted: no summary line asked for, nothing streamed.
        reader().read(GemmaReaderRequest(weakLetter, emptyList()))
        val last = engine.structuredRequests.last()
        assertThat(last.onPartial).isNull()
        assertThat(last.prompt).doesNotContain("${QaLabel.SUMMARY}:")
    }

    @Test
    @DisplayName("the picture goes only when the text is weak; the debug override always sends it; the choice is in the notes")
    fun `image only when needed`() = runBlocking<Unit> {
        val strong = reader().read(GemmaReaderRequest(strongLetter, listOf("/p1.png"))) as GemmaReaderOutcome.Stated
        assertThat(engine.structuredRequests.last().imagePaths).isEmpty()
        assertThat(strong.usedImage).isFalse()
        assertThat(strong.notes.joinToString()).contains("image=no")

        style.setAlwaysImage(true)
        val forced = reader().read(GemmaReaderRequest(strongLetter, listOf("/p1.png"))) as GemmaReaderOutcome.Stated
        assertThat(engine.structuredRequests.last().imagePaths).containsExactly("/p1.png")
        assertThat(forced.usedImage).isTrue()
        assertThat(forced.notes.joinToString()).contains("image=yes")
        assertThat(forced.notes.joinToString()).contains("debug override")

        style.setAlwaysImage(false)
        val weak = reader().read(GemmaReaderRequest(weakLetter, listOf("/p1.png"))) as GemmaReaderOutcome.Stated
        assertThat(weak.usedImage).isTrue()
    }

    @Test
    @DisplayName("sampling: the chat's own (free text) on the CPU, greedy on the GPU; the model is loaded for a reading")
    fun `sampling by backend`() = runBlocking<Unit> {
        reader().read(GemmaReaderRequest(strongLetter, emptyList()))
        val cpu = engine.structuredRequests.last()
        val free = samplingFor(SamplingPurpose.FREE_TEXT)
        assertThat(cpu.topK).isEqualTo(free.topK)
        assertThat(cpu.temperature).isEqualTo(free.temperature)

        provider.readingAccelerator = Accelerator.GPU
        reader().read(GemmaReaderRequest(strongLetter, emptyList()))
        val gpu = engine.structuredRequests.last()
        val greedy = samplingFor(SamplingPurpose.STRUCTURED)
        assertThat(gpu.topK).isEqualTo(greedy.topK)
        assertThat(gpu.temperature).isEqualTo(greedy.temperature)
        assertThat(engine.loads.last().second.accelerator).isEqualTo(Accelerator.GPU)
        assertThat(gpu.prompt).contains(strongLetter.lines.first().text)
    }

    @Test
    @DisplayName("unavailable, with the reason, when no answer comes or the letter has no text")
    fun `unavailable`() = runBlocking<Unit> {
        engine.structuredAnswer = null
        assertThat(reader().read(GemmaReaderRequest(weakLetter, emptyList()))).isInstanceOf(GemmaReaderOutcome.Unavailable::class.java)
        assertThat(reader().read(GemmaReaderRequest(GemmaLetter(emptyList(), emptyList()), listOf("/p1.png")))).isInstanceOf(GemmaReaderOutcome.Unavailable::class.java)
    }

    @Test
    @DisplayName("the switch: JSON (the default) asks for a schema, Questions asks free text, a picture-only letter is always the JSON reader's")
    fun `the switch`() = runBlocking<Unit> {
        val switched = StyleSwitchedGemmaReader(ChatEngineGemmaReader(engine, provider), QuestionAnswerGemmaReader(engine, provider, style), style)

        switched.read(GemmaReaderRequest(weakLetter, emptyList()))
        style.set(ReaderStyle.QUESTIONS)
        switched.read(GemmaReaderRequest(weakLetter, emptyList()))
        switched.read(GemmaReaderRequest(GemmaLetter(emptyList(), emptyList()), listOf("/p1.png")))

        assertThat(engine.structuredRequests.map { it.schema.isBlank() }).containsExactly(false, true, false).inOrder()
    }
}
