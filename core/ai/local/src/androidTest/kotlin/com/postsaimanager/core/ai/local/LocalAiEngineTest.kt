package com.postsaimanager.core.ai.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tests for [LocalAiEngine] — the Kotlin face of the runtime.
 *
 * Covers the behaviour the JNI layer cannot express on its own: lifecycle, streaming,
 * cancellation, and the KV-cache isolation that keeps one generation from conditioning on
 * the last.
 */
@RunWith(AndroidJUnit4::class)
class LocalAiEngineTest {

    private val modelFile = File("/data/local/tmp/spike-model.gguf")
    private val tag = "pam_spike"

    private fun engine() = LocalAiEngine(Dispatchers.IO)

    private fun config(contextTokens: Int = 1024) =
        InferenceConfig(contextTokens = contextTokens, threads = InferenceConfig.defaultThreadCount())

    private fun requireModel() {
        assumeTrue("No model at ${modelFile.path}", modelFile.exists())
        assumeTrue("Native library unavailable", LlamaNative.ensureLoaded())
    }

    @Test
    fun reportsNoModelBeforeLoading() {
        assertEquals(ModelLoadState.Idle, engine().state.value)
    }

    @Test
    fun loadingAMissingFileFailsWithoutCrashing() = runBlocking {
        val result = engine().loadFile(File("/data/local/tmp/does-not-exist.gguf"))

        assertTrue(result is PamResult.Error)
        // The failure must be typed and actionable, not an exception escaping to the caller.
        assertTrue(engine().state.value is ModelLoadState.Idle)
    }

    @Test
    fun loadsAndReportsCapabilities() = runBlocking {
        requireModel()
        val engine = engine()

        val result = engine.loadFile(modelFile, config())

        assertTrue("load failed: $result", result is PamResult.Success)
        val caps = (result as PamResult.Success).data
        Log.i(tag, "engine caps=$caps threads=${InferenceConfig.defaultThreadCount()}")

        assertTrue(caps.supportsGrammar)
        assertEquals(1024, caps.contextTokens)
        assertTrue(engine.isReady)
        assertTrue(engine.state.value is ModelLoadState.Ready)

        engine.unload()
        assertEquals(ModelLoadState.Idle, engine.state.value)
    }

    @Test
    fun streamsTokensIncrementally() = runBlocking {
        requireModel()
        val engine = engine()
        engine.loadFile(modelFile, config())

        try {
            val tokens = engine.generate(AiRequest("Name three colours.", maxTokens = 24, temperature = 0f))
                .toList()

            Log.i(tag, "stream tokenCount=${tokens.size} text=<<<${tokens.joinToString("")}>>>")

            // More than one emission is the point: a single chunk would mean it is not
            // actually streaming.
            assertTrue("expected multiple tokens, got ${tokens.size}", tokens.size > 1)
            assertTrue(tokens.joinToString("").isNotBlank())
        } finally {
            engine.unload()
        }
    }

    /**
     * The KV cache is cleared per generation, so an earlier prompt cannot condition a later
     * one. Before the fix the second generation inherited the first's context — presenting
     * as a model-quality problem rather than the state bug it was.
     */
    @Test
    fun generationsDoNotLeakContextIntoEachOther() = runBlocking {
        requireModel()
        val engine = engine()
        engine.loadFile(modelFile, config())

        try {
            val prompt = "Repeat exactly: banana"
            val first = engine.generateOnce(prompt, maxTokens = 12, temperature = 0f)
            val second = engine.generateOnce(prompt, maxTokens = 12, temperature = 0f)

            val a = (first as PamResult.Success).data
            val b = (second as PamResult.Success).data
            Log.i(tag, "isolation first=<<<$a>>> second=<<<$b>>>")

            // Greedy sampling from an identical prompt and a cleared cache is
            // deterministic — so identical output proves the cache really was cleared.
            assertEquals("KV cache leaked between generations", a, b)
        } finally {
            engine.unload()
        }
    }

    @Test
    fun cancellationStopsGenerationPromptly() = runBlocking {
        requireModel()
        val engine = engine()
        engine.loadFile(modelFile, config())

        try {
            // take(3) cancels the flow after three tokens; the engine must release native
            // generation state rather than run on to maxTokens.
            val partial = engine.generate(AiRequest("Write a very long essay.", maxTokens = 256, temperature = 0f))
                .take(3)
                .toList()

            assertEquals(3, partial.size)
            Log.i(tag, "cancel partial=<<<${partial.joinToString("")}>>>")

            // If cancellation left native state dangling, this would hang or fail.
            val after = withTimeoutOrNull(30_000) {
                engine.generateOnce("Say hello.", maxTokens = 8, temperature = 0f)
            }
            assertTrue("engine unusable after cancellation", after is PamResult.Success)
        } finally {
            engine.unload()
        }
    }

    @Test
    fun grammarConstrainsStreamedOutput() = runBlocking {
        requireModel()
        val engine = engine()
        engine.loadFile(modelFile, config())

        try {
            val grammar = "root ::= \"yes\" | \"no\"\n"
            val result = engine.generateOnce(
                prompt = "Write a paragraph about the sea.",
                maxTokens = 16,
                temperature = 0f,
                grammar = grammar,
            )

            val text = (result as PamResult.Success).data.trim()
            Log.i(tag, "engine grammar out=<<<$text>>>")
            assertTrue("outside grammar: '$text'", text in setOf("yes", "no"))
        } finally {
            engine.unload()
        }
    }
}
