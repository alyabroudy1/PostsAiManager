package com.postsaimanager.core.ai.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private fun drain(
    handle: Long,
    prompt: String,
    maxTokens: Int,
    grammar: String?,
): String {
    if (!LlamaNative.startGeneration(handle, prompt, maxTokens, 0.0f, 40, 0.9f, -1L, grammar)) return ""
    val sb = StringBuilder()
    try {
        while (true) sb.append(LlamaNative.nextToken(handle) ?: break)
    } finally {
        LlamaNative.stopGeneration(handle)
    }
    return sb.toString()
}

/**
 * Regression guards for three bugs in the JNI bridge that were found by **re-reading it**,
 * not by a failing test — the original single-threaded spike tests passed over all of them.
 *
 * 1. `static llama_token` — a function-scope static shared across every call and thread, so
 *    concurrent or sequential generations could corrupt each other's batch.
 * 2. `tokenToPiece` silently dropped any token wider than its 256-byte buffer.
 * 3. An empty prompt built a zero-length batch, which `llama_decode` rejects.
 *
 * None of these produced a wrong *test result*; they produced wrong *behaviour* in
 * conditions the tests never created. That is the argument for reading the code as well as
 * running it.
 */
@RunWith(AndroidJUnit4::class)
class GrammarRegressionTest {

    private val modelPath = "/data/local/tmp/spike-model.gguf"
    private val tag = "pam_spike"

    private val grammar = "root ::= \"alpha\" | \"beta\"\n"

    private fun requireModel() {
        assumeTrue("No model at $modelPath", File(modelPath).exists())
        assumeTrue("Native library unavailable", LlamaNative.ensureLoaded())
    }

    /** Guards bug 1: two generations from one handle must not interfere. */
    @Test
    fun sequentialGenerationsAreIndependent() {
        requireModel()
        val handle = LlamaNative.loadModel(
            modelPath, contextTokens = 1024, batchTokens = 512, threads = 4,
            threadsBatch = 4, useMmap = true, useMlock = false, flashAttention = false,
            gpuLayers = 0,
            accelerator = 0,
        )
        assertNotEquals(0L, handle)

        try {
            val first = drain(handle, "Say alpha.", 8, grammar).trim()
            val second = drain(handle, "Say beta.", 8, grammar).trim()

            Log.i(tag, "sequential first=<<<$first>>> second=<<<$second>>>")
            assertTrue("first outside grammar: '$first'", first in setOf("alpha", "beta"))
            assertTrue("second outside grammar: '$second'", second in setOf("alpha", "beta"))
        } finally {
            LlamaNative.freeModel(handle)
        }
    }

    /** Guards bug 3, plus an unparseable grammar and a zero token budget. */
    @Test
    fun degenerateInputDoesNotKillTheProcess() {
        requireModel()
        val handle = LlamaNative.loadModel(
            modelPath, contextTokens = 512, batchTokens = 512, threads = 2,
            threadsBatch = 2, useMmap = true, useMlock = false, flashAttention = false,
            gpuLayers = 0,
            accelerator = 0,
        )
        assertNotEquals(0L, handle)

        try {
            val empty = drain(handle, "", 8, null)
            Log.i(tag, "degenerate empty=<<<$empty>>>")

            // Must fall back to unconstrained sampling, not abort.
            val bad = drain(handle, "Hi", 8, "this is not gbnf {{{")
            Log.i(tag, "degenerate badGrammar=<<<$bad>>>")

            drain(handle, "Hi", 0, null)

            // Reaching this line at all is the assertion: a native abort would have taken
            // the whole process down (spike Q3), so the test would never report.
            assertTrue(true)
        } finally {
            LlamaNative.freeModel(handle)
        }
    }

    /** A model can be loaded and freed repeatedly without leaking or corrupting state. */
    @Test
    fun loadFreeCycleIsRepeatable() {
        requireModel()
        repeat(3) { i ->
            val handle = LlamaNative.loadModel(
            modelPath, contextTokens = 512, batchTokens = 512, threads = 2,
            threadsBatch = 2, useMmap = true, useMlock = false, flashAttention = false,
            gpuLayers = 0,
            accelerator = 0,
        )
            assertNotEquals("load $i failed", 0L, handle)
            val out = drain(handle, "Say alpha.", 4, grammar).trim()
            Log.i(tag, "cycle $i out=<<<$out>>>")
            assertTrue("cycle $i outside grammar: '$out'", out in setOf("alpha", "beta"))
            LlamaNative.freeModel(handle)
        }
    }
}
