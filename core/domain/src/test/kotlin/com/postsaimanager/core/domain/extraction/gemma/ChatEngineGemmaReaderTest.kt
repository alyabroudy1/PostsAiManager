package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.StructuredRequest
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeChatEngine
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The reader over the chat engine: the one-model rule (it loads the chat model), the schema it asks for, and every way it is unavailable. */
class ChatEngineGemmaReaderTest {

    private val engine = FakeChatEngine().apply { structuredAnswer = """{"category":"bill"}""" }
    private val provider = FakeActiveModelProvider(path = "/models/gemma.litertlm", runtime = ModelRuntime.LITERT_LM, supportsImages = true)
    private val letter = MiniLetter().letter

    private fun read(request: GemmaReaderRequest = GemmaReaderRequest(letter, listOf("/p1.png", "/p2.png"))) =
        runBlocking { ChatEngineGemmaReader(engine, provider).read(request) }

    @Test
    @DisplayName("it loads the chat model, asks for one answer constrained to this letter's schema with its pictures, and returns it")
    fun `answers`() {
        val outcome = read() as GemmaReaderOutcome.Answered

        assertThat(outcome.json).isEqualTo("""{"category":"bill"}""")
        assertThat(outcome.usedImage).isTrue()
        assertThat(engine.loads.single().first).isEqualTo("/models/gemma.litertlm")
        val request = engine.structuredRequests.single()
        assertThat(request.schema).isEqualTo(GemmaSchema.build(letter))
        assertThat(outcome.schema).isEqualTo(request.schema)
        assertThat(request.imagePaths).containsExactly("/p1.png", "/p2.png").inOrder()
        assertThat(request.prompt).contains("L1 Stadtwerke Beispiel GmbH")
        assertThat(request.prompt).contains("M1 | name | Stadtwerke Beispiel GmbH")
        assertThat(request.system).contains("JSON only")
        assertThat(request.temperature).isAtMost(0.2f)
        assertThat(request.timeoutMs).isEqualTo(StructuredRequest.DEFAULT_TIMEOUT_MS)
    }

    @Test
    @DisplayName("asked for a summary first, the request has two turns (the letter with the summary question, then the field guide) and hands the first answer on")
    fun `two turns`() {
        val heard = mutableListOf<String>()
        engine.leadAnswer = "A short summary."

        read(GemmaReaderRequest(letter, listOf("/p1.png"), onSummary = { heard += it }))

        val request = engine.structuredRequests.single()
        assertThat(request.leadPrompt).contains("L1 Stadtwerke Beispiel GmbH")
        assertThat(request.leadPrompt).contains("plain text")
        assertThat(request.leadPrompt).doesNotContain("ANSWER: one JSON object")
        assertThat(request.prompt).contains("ANSWER: one JSON object")
        assertThat(request.prompt).doesNotContain("L1 Stadtwerke")
        assertThat(request.system).contains("plain text")
        assertThat(heard).containsExactly("A short summary.")
    }

    @Test
    @DisplayName("not asked for a summary, or a picture-only letter, the request is the one message as before")
    fun `one turn`() {
        read()
        read(GemmaReaderRequest(GemmaLetter(emptyList(), emptyList()), listOf("/p1.png"), onSummary = {}))

        assertThat(engine.structuredRequests.map { it.leadPrompt }).containsExactly(null, null)
        assertThat(engine.structuredRequests.map { it.onLead }).containsExactly(null, null)
    }

    @Test
    @DisplayName("the text of the lines is cut to what the window holds next to the picture, the field guide after it always stays")
    fun `prompt fits the window`() {
        provider.contextTokens = 2048
        val many = GemmaLetter(letter.lines + (1..400).map { GemmaLine("L${100 + it}", 1, "body", 0f, 0f, "a line of the letter number $it".repeat(3)) }, letter.candidates)

        read(GemmaReaderRequest(many, emptyList()))

        val prompt = engine.structuredRequests.single().prompt
        assertThat(prompt.length).isAtMost(GemmaPrompt.MAX_CHARS)
        assertThat(prompt).contains("ANSWER: one JSON object")
        assertThat(prompt).contains("DATE MEANINGS:")
    }

    @Test
    @DisplayName("without image support the pictures are left out, the text reading goes on")
    fun `no image support`() {
        provider.supportsImages = false

        val outcome = read() as GemmaReaderOutcome.Answered

        assertThat(outcome.usedImage).isFalse()
        assertThat(engine.structuredRequests.single().imagePaths).isEmpty()
    }

    @Test
    @DisplayName("a letter with no text and a model that cannot take pictures has nothing to read")
    fun `image only needs images`() {
        provider.supportsImages = false

        val outcome = read(GemmaReaderRequest(GemmaLetter(emptyList(), emptyList()), listOf("/p1.png")))

        assertThat(outcome).isInstanceOf(GemmaReaderOutcome.Unavailable::class.java)
        assertThat(engine.structuredRequests).isEmpty()
    }

    @Test
    @DisplayName("a picture-only letter asks for the picture-only schema and says to read the picture")
    fun `image only`() {
        val outcome = read(GemmaReaderRequest(GemmaLetter(emptyList(), emptyList()), listOf("/p1.png"))) as GemmaReaderOutcome.Answered

        // Names as printed ("n"), the summary ("s") and the key facts ("y"): nothing for a second step to write them from.
        assertThat(outcome.schema).contains("\"n\"")
        assertThat(outcome.schema).contains("\"s\"")
        assertThat(engine.structuredRequests.single().system).contains("picture")
    }

    @Test
    @DisplayName("no chat model installed: unavailable, and nothing is loaded")
    fun `not installed`() {
        provider.path = null

        val outcome = read()

        assertThat((outcome as GemmaReaderOutcome.Unavailable).reason).contains("no chat model")
        assertThat(engine.loads).isEmpty()
    }

    @Test
    @DisplayName("a chat model that is not a LiteRT-LM model cannot constrain its answer: unavailable")
    fun `not litert`() {
        provider.runtime = ModelRuntime.LLAMA_CPP

        val outcome = read()

        assertThat((outcome as GemmaReaderOutcome.Unavailable).reason).contains("LiteRT-LM")
        assertThat(engine.structuredRequests).isEmpty()
    }

    @Test
    @DisplayName("a model that fails to load is unavailable")
    fun `load fails`() {
        engine.failNextLoadWith = "out of memory"

        val outcome = read()

        assertThat((outcome as GemmaReaderOutcome.Unavailable).reason).contains("could not be loaded")
    }

    @Test
    @DisplayName("no answer (the model is busy, the run failed or timed out) is unavailable, never an empty reading")
    fun `no answer`() {
        engine.structuredAnswer = null

        val outcome = read()

        assertThat((outcome as GemmaReaderOutcome.Unavailable).reason).contains("no answer")
    }
}
