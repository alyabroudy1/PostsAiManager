package com.postsaimanager.core.ai.local

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the `:inference` process boundary does what it was built for.
 *
 * Spike Q3 measured a C++ abort inside llama.cpp producing `SIGABRT` and
 * `Zygote: Process exited due to signal 6` — the whole app died, and no Kotlin `try/catch`
 * could intercept it. These tests kill the inference process deliberately and assert that
 * the caller survives, is told what happened, and can carry on.
 */
@RunWith(AndroidJUnit4::class)
class ProcessIsolationTest {

    private val modelFile = File("/data/local/tmp/spike-model.gguf")
    private val tag = "pam_spike"

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun engine() = RemoteAiEngine(context, kotlinx.coroutines.Dispatchers.IO)

    private fun config(contextTokens: Int = 1024) =
        InferenceConfig(contextTokens = contextTokens, threads = InferenceConfig.defaultThreadCount())

    private fun requireModel() {
        assumeTrue("No model at ${modelFile.path}", modelFile.exists())
    }

    /** @return the pid of the `:inference` process, or null if it is not running. */
    private fun inferencePid(): Int? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.runningAppProcesses
            ?.firstOrNull { it.processName.endsWith(":inference") }
            ?.pid
    }

    @Test
    fun inferenceRunsInASeparateProcess() = runBlocking {
        requireModel()
        val engine = engine()

        val result = engine.load(modelFile.absolutePath, config(1024))
        assertTrue("load failed: $result", result is PamResult.Success)

        val pid = inferencePid()
        Log.i(tag, "isolation inferencePid=$pid ownPid=${android.os.Process.myPid()}")

        assertTrue("no :inference process found", pid != null)
        // The whole point: native code is not running in the caller's process.
        assertTrue("inference shares our pid", pid != android.os.Process.myPid())

        engine.unload()
    }

    @Test
    fun generationWorksAcrossTheProcessBoundary() = runBlocking {
        requireModel()
        val engine = engine()
        engine.load(modelFile.absolutePath, config(1024))

        try {
            val tokens = engine.generate(
                AiRequest("Name three colours.", maxTokens = 24, temperature = 0f),
            ).toList()

            Log.i(tag, "isolation ipcTokens=${tokens.size} text=<<<${tokens.joinToString("")}>>>")

            // Streaming must survive IPC — a single lump would mean tokens are being
            // buffered somewhere and the UI would stop feeling live.
            assertTrue("expected streamed tokens, got ${tokens.size}", tokens.size > 1)
            assertTrue(tokens.joinToString("").isNotBlank())
        } finally {
            engine.unload()
        }
    }

    @Test
    fun grammarStillConstrainsAcrossTheBoundary() = runBlocking {
        requireModel()
        val engine = engine()
        engine.load(modelFile.absolutePath, config(1024))

        try {
            val out = engine.generate(
                AiRequest(
                    prompt = engine.formatPrompt(
                        listOf(AiChatMessage(AiChatRole.USER, "Write about the sea.")),
                    ),
                    maxTokens = 16,
                    temperature = 0f,
                    grammar = "root ::= \"yes\" | \"no\"\n",
                ),
            ).toList().joinToString("").trim()

            Log.i(tag, "isolation ipcGrammar=<<<$out>>>")
            assertTrue("outside grammar: '$out'", out in setOf("yes", "no"))
        } finally {
            engine.unload()
        }
    }

    /**
     * **The test this whole change exists for.**
     *
     * Kills the inference process outright — the same outcome a native abort produces —
     * and asserts the caller lives through it. Before isolation this would have taken the
     * test process down with it, so the test could never report a result at all.
     */
    @Test
    fun survivesTheInferenceProcessBeingKilled() = runBlocking {
        requireModel()
        val engine = engine()
        engine.load(modelFile.absolutePath, config(1024))

        val pid = inferencePid()
        assertTrue("no :inference process", pid != null)

        Log.i(tag, "isolation killing pid=$pid")
        android.os.Process.killProcess(pid!!)

        // Give the DeathRecipient a moment to observe it.
        Thread.sleep(2_000)

        // Reaching this line at all is the assertion — the caller is still alive.
        Log.i(tag, "isolation survived kill; state=${engine.state.value}")

        val state = engine.state.value
        assertTrue(
            "expected a Failed state after the crash, got $state",
            state is ModelLoadState.Failed,
        )
        // The message must reassure, not alarm: nothing the user owns was lost.
        assertTrue(
            "message should mention documents are safe: ${(state as ModelLoadState.Failed).error}",
            state.error.contains("documents", ignoreCase = true),
        )

        // And it must recover: the next request rebinds and reloads.
        val recovered = engine.generate(
            AiRequest("Say hello.", maxTokens = 8, temperature = 0f),
        ).toList().joinToString("")

        Log.i(tag, "isolation recovered=<<<$recovered>>>")
        assertTrue("engine did not recover after the crash", recovered.isNotBlank())

        engine.unload()
    }

    @Test
    fun killingInferenceDoesNotKillTheCaller() {
        requireModel()
        val before = android.os.Process.myPid()

        runBlocking {
            val engine = engine()
            engine.load(modelFile.absolutePath, config(512))
            inferencePid()?.let { android.os.Process.killProcess(it) }
            Thread.sleep(1_500)
        }

        // Same pid means we were never restarted — the crash was genuinely contained.
        assertEquals("the caller process was restarted", before, android.os.Process.myPid())
    }
}
