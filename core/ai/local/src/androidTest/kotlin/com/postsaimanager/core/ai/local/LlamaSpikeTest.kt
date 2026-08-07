package com.postsaimanager.core.ai.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis

/** Drains the pull-based token stream into one string — mirrors what LocalAiEngine does. */
private fun drain(
    handle: Long,
    prompt: String,
    maxTokens: Int,
    temperature: Float,
    grammar: String?,
): String {
    if (!LlamaNative.startGeneration(handle, prompt, maxTokens, temperature, grammar)) return ""
    val sb = StringBuilder()
    try {
        while (true) sb.append(LlamaNative.nextToken(handle) ?: break)
    } finally {
        LlamaNative.stopGeneration(handle)
    }
    return sb.toString()
}

/**
 * Phase 7.2 spike — questions Q2 and Q4, on real arm64 hardware.
 *
 * Not a regression suite. It exists to produce **numbers**, logged under the `pam_spike`
 * tag, that decide whether llama.cpp is the right runtime and whether Phase 8's tool layer
 * is viable. See documentation/06-llama-spike.md
 *
 * Requires a model pushed to the device first:
 * ```
 * adb push qwen05b-q4_0.gguf /data/local/tmp/spike-model.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LlamaSpikeTest {

    private val modelPath = "/data/local/tmp/spike-model.gguf"
    private val tag = "pam_spike"

    private fun requireModel() {
        assumeTrue("No model at $modelPath — push one first", File(modelPath).exists())
        assumeTrue("Native library unavailable", LlamaNative.ensureLoaded())
    }

    @Test
    fun q2_loadsModelAndGeneratesTokens() {
        requireModel()

        Log.i(tag, "system: ${LlamaNative.systemInfo()}")

        var handle = 0L
        val loadMs = measureTimeMillis {
            handle = LlamaNative.loadModel(modelPath, contextTokens = 2048, threads = 4)
        }
        Log.i(tag, "Q2 load_ms=$loadMs handle=$handle")
        assertNotEquals("model failed to load", 0L, handle)

        try {
            // Qwen2.5 chat template. A base-style prompt would still generate, but this
            // exercises the path the app will actually use.
            val prompt = "<|im_start|>user\nName three colours.<|im_end|>\n<|im_start|>assistant\n"

            val maxTokens = 64
            var output = ""
            val genMs = measureTimeMillis {
                output = drain(handle, prompt, maxTokens, 0.0f, null)
            }

            val tokensPerSec = if (genMs > 0) maxTokens * 1000.0 / genMs else 0.0
            Log.i(tag, "Q2 gen_ms=$genMs tok_per_sec=%.1f".format(tokensPerSec))
            Log.i(tag, "Q2 output=<<<$output>>>")

            assertTrue("generated nothing", output.isNotBlank())
        } finally {
            LlamaNative.freeModel(handle)
        }
    }

    /**
     * **Q4 — the question Phase 8 depends on.**
     *
     * A grammar admitting only `red`, `green` or `blue` is imposed on a prompt that invites
     * a sentence. If grammar-constrained sampling works, the model *cannot* produce anything
     * else — invalid output is unreachable, not merely unlikely. That is what makes tool
     * calling viable on small models with no native tool support.
     */
    @Test
    fun q4_grammarConstrainsOutput() {
        requireModel()

        val handle = LlamaNative.loadModel(modelPath, contextTokens = 2048, threads = 4)
        assertNotEquals(0L, handle)

        try {
            val grammar = """
                root ::= "red" | "green" | "blue"
            """.trimIndent()

            val prompt = "<|im_start|>user\nWrite a long paragraph about the ocean.<|im_end|>\n" +
                "<|im_start|>assistant\n"

            val unconstrained = drain(handle, prompt, 32, 0.0f, null)
            Log.i(tag, "Q4 unconstrained=<<<$unconstrained>>>")

            val constrained = drain(handle, prompt, 32, 0.0f, grammar)
            Log.i(tag, "Q4 constrained=<<<$constrained>>>")

            val trimmed = constrained.trim()
            assertTrue(
                "grammar did not constrain output: '$trimmed'",
                trimmed in setOf("red", "green", "blue"),
            )
        } finally {
            LlamaNative.freeModel(handle)
        }
    }

    /** JSON-shaped grammar — closer to what a real tool call needs. */
    @Test
    fun q4b_jsonGrammarProducesValidJson() {
        requireModel()

        val handle = LlamaNative.loadModel(modelPath, contextTokens = 2048, threads = 4)
        assertNotEquals(0L, handle)

        try {
            val grammar = """
                root   ::= "{" ws "\"tool\"" ws ":" ws tool ws "}"
                tool   ::= "\"search_documents\"" | "\"create_profile\""
                ws     ::= " "?
            """.trimIndent()

            val prompt = "<|im_start|>user\nFile this letter.<|im_end|>\n<|im_start|>assistant\n"
            val out = drain(handle, prompt, 48, 0.0f, grammar).trim()

            Log.i(tag, "Q4b json=<<<$out>>>")
            assertTrue("not a JSON object: '$out'", out.startsWith("{") && out.endsWith("}"))
            assertTrue("missing tool key: '$out'", out.contains("\"tool\""))
        } finally {
            LlamaNative.freeModel(handle)
        }
    }
}
