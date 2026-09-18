package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeAiEngine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [AiExtractionUseCase].
 *
 * The model is faked, so these are about everything *around* generation: that the page is
 * described with its layout, that the grammar is actually attached, that a truncated or
 * nonsensical answer degrades instead of crashing, and that a schema-valid but senseless
 * result is cleaned up before it reaches storage.
 *
 * Whether the real model reads a real letter correctly is a device test — no fake can
 * answer it.
 */
class AiExtractionUseCaseTest {

    private val engine = FakeAiEngine()
    private val models = FakeActiveModelProvider()
    private val extract = AiExtractionUseCase(engine, models)

    private fun block(text: String, left: Float, top: Float) = OcrBlock(
        text = text,
        bounds = TextBounds(left, top, left + 0.3f, top + 0.05f),
        confidence = 0.9f,
    )

    private val page = listOf(
        block("Jobcenter Berlin Mitte", 0.08f, 0.04f),
        block("Frau\nAylin Mustermann", 0.08f, 0.20f),
        block("Aktenzeichen: BG 1234/5678", 0.60f, 0.20f),
        // Deliberately does not name "Layla" — goodAnswer below claims her anyway, so that
        // the mismatch between claim and page stands in for a model hallucinating a
        // specific name the source text never gave it.
        block("Ihre Ehefrau ist ebenfalls betroffen.", 0.08f, 0.55f),
    )

    private val goodAnswer = """
        {"language":"de","documentType":"Widerspruchsbescheid","subject":"Widerspruch",
         "entities":[
           {"name":"Jobcenter Berlin Mitte","kind":"AUTHORITY","role":"SENDER","relation":"","confidence":0.95},
           {"name":"Aylin Mustermann","kind":"PERSON","role":"RECIPIENT","relation":"","confidence":0.9},
           {"name":"Layla","kind":"PERSON","role":"MENTIONED","relation":"spouse of the recipient","confidence":0.6}
         ],
         "facts":[
           {"label":"Aktenzeichen","value":"BG 1234/5678","kind":"REFERENCE","confidence":0.92},
           {"label":"Frist","value":"31.01.2026","kind":"DEADLINE","confidence":0.85}
         ]}
    """.trimIndent()

    @Nested
    @DisplayName("What the model is asked")
    inner class Request {

        @Test
        @DisplayName("the page is described with positions, not as flat text")
        fun `sends layout`() = runTest {
            engine.response = goodAnswer
            extract(page)

            val userMessage = engine.lastMessages.last().content
            // Position is the whole reason one prompt can work across sender formats.
            assertThat(userMessage).contains("address block")
            assertThat(userMessage).contains("reference block")
            assertThat(userMessage).contains("BG 1234/5678")
        }

        @Test
        fun `attaches the grammar`() = runTest {
            engine.response = goodAnswer
            extract(page)

            // Without this the model may answer in prose, and the whole class of parsing
            // bugs this design avoids comes back.
            val grammar = engine.lastRequest?.grammar
            assertThat(grammar).isNotNull()
            assertThat(grammar).contains("SENDER_CONTACT")
            assertThat(grammar).contains("DEADLINE")
        }

        @Test
        @DisplayName("temperature is near zero — reading, not writing")
        fun `is near deterministic`() = runTest {
            engine.response = goodAnswer
            extract(page)

            // Two runs over one document must not disagree, or the merge would flag
            // sampling noise as the document having changed.
            assertThat(engine.lastRequest!!.temperature).isLessThan(0.3f)
        }

        @Test
        @DisplayName("the window budgeted against is the window loaded with")
        fun `budget follows the provider`() = runTest {
            engine.response = goodAnswer
            engine.isReady = false
            // The device cap, not the model's catalogued maximum. Loading 32k of KV cache on
            // a phone with 2.5 GB free aborted inside llama_decode — a native crash, so
            // nothing catchable.
            models.contextTokens = 2048

            extract(page)

            assertThat(engine.lastMessages.last().content.length).isAtMost(2048 * 3)
        }

        @Test
        fun `a long page is truncated to fit the context`() = runTest {
            engine.response = goodAnswer
            val huge = List(400) { block("Ein sehr langer Absatz mit viel Inhalt $it", 0.08f, 0.5f) }

            extract(huge, contextTokens = 2048)

            // Overflowing the window does not error, it silently drops the *start* of the
            // prompt — including the instructions — so the budget has to be respected here.
            assertThat(engine.lastMessages.last().content.length).isLessThan(2048 * 3)
        }
    }

    @Nested
    @DisplayName("Reading the answer")
    inner class Parsing {

        @Test
        fun `entities carry kind, role and relation`() = runTest {
            engine.response = goodAnswer

            val result = (extract(page) as PamResult.Success).data

            assertThat(result.language).isEqualTo("de")
            assertThat(result.entities).hasSize(3)

            val sender = result.sender!!
            assertThat(sender.name).isEqualTo("Jobcenter Berlin Mitte")
            assertThat(sender.kind).isEqualTo(EntityKind.AUTHORITY)

            val layla = result.entities.single { it.name == "Layla" }
            assertThat(layla.role).isEqualTo(EntityRole.MENTIONED)
            assertThat(layla.relation).contains("spouse")
        }

        @Test
        fun `facts distinguish a deadline from the letter date`() = runTest {
            engine.response = goodAnswer

            val result = (extract(page) as PamResult.Success).data

            // Only a DEADLINE should ever become a reminder; the letter's own date must not.
            assertThat(result.deadline?.value).isEqualTo("31.01.2026")
            assertThat(result.facts.single { it.kind == FactKind.REFERENCE }.value)
                .isEqualTo("BG 1234/5678")
        }

        @Test
        @DisplayName("confidence decides linking, and is exposed for it")
        fun `splits confident from needing review`() = runTest {
            engine.response = goodAnswer

            val result = (extract(page) as PamResult.Success).data

            // Jobcenter and Aylin are both copied straight from a block on the page, so
            // ExtractionConfidence grounds them at the top band. Layla's name is nowhere in
            // `page` (see its definition above) — the model's self-reported 0.6 is discarded
            // entirely, and it is the *lack of grounding* that puts her in review, not a
            // number the model happened to attach to her.
            assertThat(result.confident().map { it.name })
                .containsExactly("Jobcenter Berlin Mitte", "Aylin Mustermann")
            assertThat(result.needingReview().map { it.name }).containsExactly("Layla")
        }
    }

    @Nested
    @DisplayName("When the answer is unusable")
    inner class Degradation {

        @Test
        fun `truncated json is an error, not a crash`() = runTest {
            // The grammar prevents malformed output but not output cut off at the token
            // limit, which is valid-so-far and not valid JSON.
            engine.response = """{"language":"de","entities":[{"name":"Jobcen"""

            assertThat(extract(page)).isInstanceOf(PamResult.Error::class.java)
        }

        @Test
        fun `an empty answer is an error`() = runTest {
            engine.response = "   "
            assertThat(extract(page)).isInstanceOf(PamResult.Error::class.java)
        }

        @Test
        fun `a dead engine reports rather than throwing`() = runTest {
            engine.failWith = IllegalStateException("native crash")
            assertThat(extract(page)).isInstanceOf(PamResult.Error::class.java)
        }

        @Test
        fun `no model installed is reported, not attempted`() = runTest {
            engine.isReady = false
            models.path = null
            assertThat(extract(page)).isInstanceOf(PamResult.Error::class.java)
        }

        @Test
        @DisplayName("a model that is installed but not loaded is loaded on demand")
        fun `loads when needed`() = runTest {
            engine.isReady = false
            engine.response = goodAnswer

            // Processing runs in the background after a scan, when nothing has yet had
            // reason to load a model. Failing because the user has not opened chat would
            // be arbitrary.
            val result = extract(page)

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            assertThat(engine.isReady).isTrue()
        }

        @Test
        fun `an empty page needs no model at all`() = runTest {
            engine.isReady = false
            val result = extract(emptyList())

            // Nothing to read is not a failure, and must not require an engine.
            assertThat((result as PamResult.Success).data.entities).isEmpty()
        }
    }

    @Nested
    @DisplayName("Cleaning up what the grammar cannot prevent")
    inner class Sanitising {

        @Test
        @DisplayName("a nameless entity is dropped, however confident")
        fun `drops blank names`() = runTest {
            engine.response = """
                {"language":"de","documentType":"","subject":"","entities":[
                  {"name":"  ","kind":"PERSON","role":"SENDER","relation":"","confidence":0.99}
                ],"facts":[]}
            """.trimIndent()

            // A grammar constrains shape, not sense. Storing this would create a nameless
            // profile.
            assertThat((extract(page) as PamResult.Success).data.entities).isEmpty()
        }

        @Test
        fun `the same entity named twice in one role is one entity`() = runTest {
            engine.response = """
                {"language":"de","documentType":"","subject":"","entities":[
                  {"name":"Jobcenter Berlin","kind":"AUTHORITY","role":"SENDER","relation":"","confidence":0.9},
                  {"name":"jobcenter berlin","kind":"AUTHORITY","role":"SENDER","relation":"","confidence":0.8}
                ],"facts":[]}
            """.trimIndent()

            // Otherwise a letter that names its sender in the header and the footer creates
            // the profile twice.
            assertThat((extract(page) as PamResult.Success).data.entities).hasSize(1)
        }

        @Test
        fun `out of range confidence is clamped`() = runTest {
            engine.response = """
                {"language":"de","documentType":"","subject":"","entities":[
                  {"name":"Jobcenter","kind":"AUTHORITY","role":"SENDER","relation":"","confidence":9.0}
                ],"facts":[]}
            """.trimIndent()

            assertThat((extract(page) as PamResult.Success).data.entities.single().confidence)
                .isAtMost(1f)
        }
    }
}
