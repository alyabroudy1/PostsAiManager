package com.postsaimanager.core.ai.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies that prompts are formatted with the **model's own** chat template.
 *
 * Hard-coding one template does not survive a catalogue: Qwen uses ChatML, Gemma uses
 * `<start_of_turn>`, Llama 3 uses its own headers. The wrong template does not error — it
 * silently degrades output, so this needs asserting rather than assuming.
 */
@RunWith(AndroidJUnit4::class)
class ChatTemplateTest {

    private val modelFile = File("/data/local/tmp/spike-model.gguf")
    private val tag = "pam_spike"

    private fun requireModel() {
        assumeTrue("No model at ${modelFile.path}", modelFile.exists())
        assumeTrue("Native library unavailable", LlamaNative.ensureLoaded())
    }

    @Test
    fun usesTheModelsOwnTemplate() = runBlocking {
        requireModel()
        val engine = LocalAiEngine(Dispatchers.IO)

        try {
            val result = engine.loadFile(modelFile, contextTokens = 1024)
            val caps = (result as PamResult.Success).data

            Log.i(tag, "template hasNative=${caps.hasNativeChatTemplate}")

            val prompt = engine.formatPrompt(
                listOf(
                    AiChatMessage(AiChatRole.SYSTEM, "You are helpful."),
                    AiChatMessage(AiChatRole.USER, "Hello"),
                ),
            )
            Log.i(tag, "template prompt=<<<$prompt>>>")

            // Both roles must survive formatting, whichever template was applied.
            assertTrue("system message missing", prompt.contains("You are helpful."))
            assertTrue("user message missing", prompt.contains("Hello"))
            // It must end with the assistant turn opener, or the model continues the
            // user's message instead of replying.
            assertTrue("no assistant prefix: $prompt", prompt.trimEnd().endsWith("assistant"))
        } finally {
            engine.unload()
        }
    }

    @Test
    fun multiTurnHistoryIsFormatted() = runBlocking {
        requireModel()
        val engine = LocalAiEngine(Dispatchers.IO)

        try {
            engine.loadFile(modelFile, contextTokens = 1024)

            val prompt = engine.formatPrompt(
                listOf(
                    AiChatMessage(AiChatRole.USER, "First question"),
                    AiChatMessage(AiChatRole.ASSISTANT, "First answer"),
                    AiChatMessage(AiChatRole.USER, "Second question"),
                ),
            )
            Log.i(tag, "template multiturn=<<<$prompt>>>")

            // Ordering must be preserved — history out of order changes the meaning.
            val firstQ = prompt.indexOf("First question")
            val firstA = prompt.indexOf("First answer")
            val secondQ = prompt.indexOf("Second question")
            assertTrue("history lost", firstQ >= 0 && firstA > firstQ && secondQ > firstA)
        } finally {
            engine.unload()
        }
    }
}
