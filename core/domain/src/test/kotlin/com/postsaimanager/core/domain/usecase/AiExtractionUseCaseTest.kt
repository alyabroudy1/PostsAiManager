package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.Oracle
import com.postsaimanager.core.domain.extraction.v2.Prepared
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeAiEngine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [AiExtractionUseCase], the thin orchestrator: it loads the model, hands the pages to the
 * v2 pipeline, and returns the adapted result. The model is faked, so these are about everything around
 * generation; whether a real model reads a real letter is a device test.
 */
class AiExtractionUseCaseTest {

    private val engine = FakeAiEngine()
    private val models = FakeActiveModelProvider()
    private val extract = AiExtractionUseCase(engine, models)

    private val letter = Letters.n1
    private val blocks: List<OcrBlock> = letter.pages.flatten()
    private val counts: List<Int> = letter.pages.map { it.size }

    /** Answers the structured call and the text call the way a correct model would. */
    private fun scripted(l: com.postsaimanager.core.domain.extraction.v2.Letter = letter) {
        val structured = Oracle.structured(l, Prepared(l.pages)).json
        val text = Oracle.text(l)
        engine.responder = { request: AiRequest -> if (request.grammar!!.contains("\\\"tc\\\":")) structured else text }
    }

    @Nested
    @DisplayName("What the model is asked")
    inner class Requests {

        @Test
        fun `two calls, the structured reading first and then the free text`() = runTest {
            scripted()
            extract(blocks, pageBlockCounts = counts)
            assertThat(engine.generateRequests).hasSize(2)
            assertThat(engine.generateRequests[0].grammar).contains("\\\"tc\\\":")
            assertThat(engine.generateRequests[1].grammar).contains("\\\"qs\\\":")
            assertThat(engine.generateRequests[1].grammar).doesNotContain("\\\"tc\\\":")
        }

        @Test
        fun `the first call carries the letter with positions and the candidate table`() = runTest {
            scripted()
            extract(blocks, pageBlockCounts = counts)
            val prompt = engine.generateRequests[0].prompt
            assertThat(prompt).contains("=== PAGE 1 ===")
            assertThat(prompt).contains("[address-field]")
            assertThat(prompt).contains("[info-block]")
            assertThat(prompt).contains("CANDIDATES")
            assertThat(prompt).contains("64,98")
            assertThat(prompt).contains("(near:")
        }

        @Test
        fun `the second call carries the letter again and the type the first one chose`() = runTest {
            scripted()
            extract(blocks, pageBlockCounts = counts)
            val prompt = engine.generateRequests[1].prompt
            assertThat(prompt).contains("DOCUMENT TYPE: reminder_dunning")
            assertThat(prompt).contains("=== PAGE 1 ===")
            assertThat(prompt).doesNotContain("CANDIDATES")
        }

        @Test
        @DisplayName("greedy and without thinking: reading, not writing")
        fun `sampling is the extraction path's own`() = runTest {
            scripted()
            extract(blocks, pageBlockCounts = counts)
            for (request in engine.generateRequests) {
                assertThat(request.temperature).isEqualTo(0f)
                assertThat(request.thinkingEnabled).isFalse()
                assertThat(request.grammar).isNotNull()
            }
            assertThat(engine.generateRequests[0].maxTokens).isEqualTo(640)
            assertThat(engine.generateRequests[1].maxTokens).isEqualTo(384)
        }

        @Test
        fun `the grammar only lists ids that are in the candidate table`() = runTest {
            scripted()
            extract(blocks, pageBlockCounts = counts)
            val request = engine.generateRequests[0]
            val ids = Regex("^([A-Z]{1,2}\\d+): ", RegexOption.MULTILINE).findAll(request.prompt).map { it.groupValues[1] }.toSet()
            val inGrammar = Regex("\\\\\"([A-Z]{1,2}\\d+)\\\\\"").findAll(request.grammar!!).map { it.groupValues[1] }.toSet()
            assertThat(ids).isNotEmpty()
            assertThat(ids).containsAtLeastElementsIn(inGrammar)
        }

        @Test
        @DisplayName("the window budgeted against is the window loaded with")
        fun `budget follows the provider`() = runTest {
            scripted()
            engine.isReady = false
            models.contextTokens = 2048
            val huge = (1..3).map { p -> (1..80).map { OcrBlock("Ein sehr langer Absatz mit viel Inhalt $p.$it", TextBounds(0.1f, 0.05f + 0.01f * it, 0.9f, 0.06f + 0.01f * it), 0.9f) } }

            extract(huge.flatten(), pageBlockCounts = huge.map { it.size })

            // Overflowing the window does not error, it silently drops the start of the prompt,
            // including the instructions, so the budget has to be respected.
            for (request in engine.generateRequests) assertThat(request.prompt.length).isLessThan((2048 * 2.5).toInt())
        }

        @Test
        fun `a model that is installed but not loaded is loaded on demand, once`() = runTest {
            scripted()
            engine.isReady = false
            extract(blocks, pageBlockCounts = counts)
            assertThat(engine.isReady).isTrue()
            assertThat(engine.loadCalls).hasSize(1)
            assertThat(engine.loadCalls.single().second.contextTokens).isEqualTo(models.contextTokens)
        }
    }

    @Nested
    @DisplayName("What comes back")
    inner class Results {

        @Test
        fun `the type, the roles, the fields and the texts of the letter`() = runTest {
            scripted()
            val u = (extract(blocks, pageBlockCounts = counts) as PamResult.Success).data
            assertThat(u.modelUsed).isTrue()
            assertThat(u.documentType).isEqualTo("reminder_dunning")
            assertThat(u.language).isEqualTo("de")
            assertThat(u.entities.single { it.role == EntityRole.SENDER }.name).isEqualTo("Nordlicht Mobilfunk GmbH")
            assertThat(u.entities.single { it.role == EntityRole.RECIPIENT }.name).isEqualTo("Erika Mustermann")
            assertThat(u.facts.single { it.label == "Amount" }.value).isEqualTo("64,98 €")
            assertThat(u.title).isNotEmpty()
            assertThat(u.suggestedQuestions).hasSize(3)
        }

        @Test
        fun `a letter read whole records no truncation`() = runTest {
            scripted()
            val u = (extract(blocks, pageBlockCounts = counts) as PamResult.Success).data
            assertThat(u.inputTruncation).isNull()
        }

        @Test
        fun `a letter cut to fit records exact coverage and, given page boundaries, a page estimate`() = runTest {
            scripted()
            val huge = (1..3).map { p -> (1..120).map { OcrBlock("Ein sehr langer Absatz mit viel Inhalt $p.$it", TextBounds(0.1f, 0.05f + 0.005f * it, 0.9f, 0.055f + 0.005f * it), 0.9f) } }
            val withPages = (extract(huge.flatten(), contextTokens = 2048, pageBlockCounts = huge.map { it.size }) as PamResult.Success).data
            val t = withPages.inputTruncation!!
            assertThat(t.charactersRead).isLessThan(t.totalCharacters)
            assertThat(t.totalPages).isEqualTo(3)
            assertThat(t.pagesRead).isAtMost(3)

            val withoutPages = (extract(huge.flatten(), contextTokens = 2048) as PamResult.Success).data
            val u = withoutPages.inputTruncation!!
            assertThat(u.charactersRead).isLessThan(u.totalCharacters)
            assertThat(u.pagesRead).isNull()
            assertThat(u.totalPages).isNull()
        }
    }

    @Nested
    @DisplayName("Without a usable model")
    inner class Degradation {

        private fun assertFoundOnly(u: DocumentUnderstanding) {
            assertThat(u.modelUsed).isFalse()
            assertThat(u.entities).isEmpty()
            assertThat(u.documentType).isEmpty()
            assertThat(u.facts).isNotEmpty()
            assertThat(u.facts.all { it.label.startsWith("Found ") }).isTrue()
        }

        @Test
        fun `no model installed gives the found values, marked and roleless`() = runTest {
            models.path = null
            val result = extract(blocks, pageBlockCounts = counts)
            assertFoundOnly((result as PamResult.Success).data)
            assertThat(engine.generateRequests).isEmpty()
        }

        @Test
        fun `a model that will not load gives the found values`() = runTest {
            engine.loadFailsWith = PamResult.Error(PamError.ModelNotLoaded("test"))
            assertFoundOnly((extract(blocks, pageBlockCounts = counts) as PamResult.Success).data)
            assertThat(engine.generateRequests).isEmpty()
        }

        @Test
        fun `a dead engine reports rather than throwing and gives the found values`() = runTest {
            engine.failWith = IllegalStateException("native crash")
            assertFoundOnly((extract(blocks, pageBlockCounts = counts) as PamResult.Success).data)
        }

        @Test
        fun `an unreadable answer gives the found values`() = runTest {
            engine.response = "I am sorry, I cannot help with that."
            assertFoundOnly((extract(blocks, pageBlockCounts = counts) as PamResult.Success).data)
        }

        @Test
        fun `a structured answer cut off at the token limit gives the found values`() = runTest {
            engine.response = """{"type":"bill","tc":"HIGH","lang":"de","parties":[{"r":"SENDER","id":"O1","k":"COMP"""
            assertFoundOnly((extract(blocks, pageBlockCounts = counts) as PamResult.Success).data)
        }

        @Test
        fun `an empty page needs no model at all`() = runTest {
            engine.isReady = false
            models.path = null
            val result = extract(emptyList())
            assertThat((result as PamResult.Success).data.entities).isEmpty()
            assertThat(engine.generateRequests).isEmpty()
        }

        @Test
        fun `a free text call that fails leaves the reading intact`() = runTest {
            val structured = Oracle.structured(letter, Prepared(letter.pages)).json
            engine.responder = { request -> if (request.grammar!!.contains("\\\"tc\\\":")) structured else "not json" }
            val u = (extract(blocks, pageBlockCounts = counts) as PamResult.Success).data
            assertThat(u.modelUsed).isTrue()
            assertThat(u.documentType).isEqualTo("reminder_dunning")
            assertThat(u.title).isEmpty()
            assertThat(u.facts.any { it.label == "Amount" }).isTrue()
        }
    }
}
