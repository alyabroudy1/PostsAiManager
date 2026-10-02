package com.postsaimanager.core.ai.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.model.InferenceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The prompt session's rollback contract on the real model (a hybrid, recurrent one: Qwen3.5), on the phone:
 * every question starts from exactly the prefix, whatever was asked before it, and a chat or a one-shot
 * generation in between costs one re-read of the prefix and nothing else.
 *
 * Model: `/data/local/tmp/b8/text.gguf` (skipped when absent).
 */
@RunWith(AndroidJUnit4::class)
class PromptSessionTest {

    private val model = File("/data/local/tmp/b8/text.gguf")
    private val tag = "b8"

    private companion object {
        const val LETTER = """LETTER
=== PAGE 1 ===
[letterhead] Stadtwerke Beispielstadt GmbH
[address-field] Frau / Erika Mustermann / Musterstraße 12 / 54321 Beispielort
[info-block] Rechnungsnummer: 2026-0815 Datum: 05.08.2026 Kundennummer: 4402917
[body] Sehr geehrte Frau Mustermann, für den Zeitraum Juli 2026 berechnen wir Ihnen 64,98 € brutto. Bitte zahlen Sie bis zum 19.08.2026.
CANDIDATES
M1: Stadtwerke Beispielstadt GmbH [letterhead] p.1
M2: Erika Mustermann [address-field] p.1
D1: 05.08.2026 (near: "Datum") p.1
D2: 19.08.2026 p.1
A1: 64,98 € p.1"""

        fun grammarOf(vararg choices: String) = "root ::= " + choices.joinToString(" | ") { "\"$it\"" }
    }

    @Test
    fun everyQuestionStartsFromThePrefix() = runBlocking {
        assumeTrue("no model staged", model.exists())
        val engine = LocalAiEngine(Dispatchers.IO)
        val config = InferenceConfig(contextTokens = 4096, threads = InferenceConfig.defaultThreadCount())
        assertTrue(engine.load(model.absolutePath, config) is PamResult.Success)

        val mark = "@@Q@@"
        val rendered = engine.formatPrompt(
            listOf(
                AiChatMessage(AiChatRole.SYSTEM, "You read a letter and answer one question about it with one word."),
                AiChatMessage(AiChatRole.USER, LETTER + mark),
            ),
        )
        val prefix = rendered.substringBefore(mark)
        val tail = rendered.substringAfter(mark)
        fun q(text: String) = "\n\nQUESTION: $text" + tail

        val who = q("Who wrote and sent this letter? Answer M1 or M2.") to grammarOf("M1", "M2")
        val date = q("Which is the date of the letter? Answer D1 or D2.") to grammarOf("D1", "D2")
        val amount = q("Which amount is to be paid? Answer A1 or NONE.") to grammarOf("A1", "NONE")

        val tokens = (engine.open(prefix) as PamResult.Success).data
        assertTrue("prefix tokens $tokens", tokens > 100)
        assertTrue(engine.countTokens(LETTER)!! > 100)

        suspend fun ask(p: Pair<String, String>) = (engine.ask(p.first, p.second, 16) as PamResult.Success).data
        val a1 = ask(who)
        val a2 = ask(date)
        val a3 = ask(amount)
        Log.i(tag, "first round: $a1 $a2 $a3")

        // Reversed order, then again: the same answers, so no question saw an earlier one.
        assertEquals(a3, ask(amount))
        assertEquals(a2, ask(date))
        assertEquals(a1, ask(who))
        assertEquals(a1, ask(who))

        // The same question as a plain one-shot generation over the whole text: the session equals a fresh decode.
        val fresh = engine.generate(
            AiRequest(prompt = prefix + who.first, maxTokens = 16, temperature = 0f, grammar = who.second, thinkingEnabled = false),
        ).toList().joinToString("").trim()
        Log.i(tag, "one-shot: $fresh session: $a1")
        assertEquals(fresh, a1)

        // A chat in between takes the KV cache over; the next question re-reads the prefix and answers the same.
        val primed = engine.ensureChatSession("c1", "You are a helpful assistant.", emptyList())
        assertTrue(primed)
        assertEquals(a2, ask(date))
        // ...and the chat re-primes afterwards, as it does after a one-shot generation.
        assertTrue(engine.ensureChatSession("c1", "You are a helpful assistant.", emptyList()))

        engine.close()
        engine.close() // safe when none is open
        assertTrue(engine.countTokens(LETTER)!! > 100) // still answers with no session
        engine.unload()
    }
}
