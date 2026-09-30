package com.postsaimanager.core.ai.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.model.InferenceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Speed probe and equivalence check of the prompt session's scoring, on the phone (synthetic letter text only).
 *
 *  - prefill throughput of a letter-sized prefix, per thread count (`threads`, default `4,5,6,8`; `threadsBatch` when
 *    given is used for every entry, otherwise it equals the entry),
 *  - the cost of one score against the number of tokens of the continuation (is a score bound by compute or by a fixed
 *    per-call cost?),
 *  - decode speed of a short grammar-constrained answer,
 *  - the prefix tree: scores of `shared + continuation` through the shared level must equal the flat scores.
 *
 * Arguments: `model` (default /data/local/tmp/z10/text.gguf), `threads`, `threadsBatch`, `prefixLines` (default 40).
 * Results are logged under the tag `speedprobe` (one `RESULT` line per measurement); the native layer logs the decode and
 * rollback milliseconds of every score under `pam_llama`.
 */
@RunWith(AndroidJUnit4::class)
class SpeedProbeTest {

    private val args get() = InstrumentationRegistry.getArguments()
    private val tag = "speedprobe"

    private fun letter(lines: Int): String = buildString {
        append("LETTER\n=== PAGE 1 ===\n")
        for (i in 0 until lines) {
            append("[body] Rechnung ${2026_0000 + i * 37}: Sehr geehrte Frau Mustermann, für den Zeitraum Monat ${i % 12 + 1} berechnen wir ")
            append("Ihnen ${(i * 7 + 13) % 100},${(i * 3) % 100} € brutto. Zahlung bis zum ${i % 28 + 1}.0${i % 9 + 1}.2026.\n")
        }
    }

    private fun pct(values: List<Double>, p: Double) = values.sorted()[((values.size - 1) * p).toInt()]

    @Test
    fun probe() = runBlocking {
        val model = File(args.getString("model") ?: "/data/local/tmp/z10/text.gguf")
        assumeTrue("no model staged at ${model.path}", model.exists())
        val threadList = (args.getString("threads") ?: "4,5,6,8").split(",").map { it.trim().toInt() }
        val fixedBatch = args.getString("threadsBatch")?.toInt()
        val lines = args.getString("prefixLines")?.toInt() ?: 40

        for (t in threadList) {
            val engine = LocalAiEngine(Dispatchers.IO)
            val config = InferenceConfig(contextTokens = 6144, threads = t, threadsBatch = fixedBatch ?: t)
            assertTrue(engine.load(model.absolutePath, config) is PamResult.Success)

            val mark = "@@Q@@"
            val rendered = engine.formatPrompt(
                listOf(
                    AiChatMessage(AiChatRole.SYSTEM, "You read one scanned letter. Answer with the single word Yes or No."),
                    AiChatMessage(AiChatRole.USER, letter(lines) + mark),
                ),
            )
            val prefix = rendered.substringBefore(mark)
            val tail = rendered.substringAfter(mark)

            // Prefill throughput, twice (the first includes page faults of the mmapped weights).
            val openMs = ArrayList<Double>()
            var prefixTokens = 0
            repeat(2) {
                val t0 = System.nanoTime()
                prefixTokens = (engine.open(prefix) as PamResult.Success).data
                openMs += (System.nanoTime() - t0) / 1e6
            }
            val best = openMs.drop(1).min()
            Log.i(tag, String.format(Locale.ROOT, "RESULT threads=%d/%d prefill tokens=%d ms=%.0f tok/s=%.1f (first %.0f)", t, fixedBatch ?: t, prefixTokens, best, prefixTokens / (best / 1000.0), openMs[0]))

            // One score against the continuation length.
            val filler = "Is «Stadtwerke Beispielstadt GmbH» (context: printed on the line: Stadtwerke Beispielstadt GmbH; line above: Rechnung; line below: Musterstraße 12) the sender of the letter?"
            for (words in (args.getString("words") ?: "1,4").split(",").map { it.trim().toInt() }) {
                val text = "\n\n" + (filler + " ").repeat(words) + "Answer:" + tail
                val tokens = engine.countTokens(text)!!
                val times = ArrayList<Double>()
                repeat(4) {
                    val t0 = System.nanoTime()
                    assertTrue(engine.score(listOf(text), "Yes", "No") is PamResult.Success)
                    times += (System.nanoTime() - t0) / 1e6
                }
                Log.i(tag, String.format(Locale.ROOT, "RESULT threads=%d/%d score tokens=%d ms(min)=%.0f ms(median)=%.0f tok/s=%.1f", t, fixedBatch ?: t, tokens, times.min(), pct(times, 0.5), tokens / (times.min() / 1000.0)))
            }

            // Decode speed: a 40-token answer under no grammar.
            val q = "\n\nWrite two sentences about the letter." + tail
            val t0 = System.nanoTime()
            val answer = (engine.ask(q, "", 40) as PamResult.Success).data
            val askMs = (System.nanoTime() - t0) / 1e6
            Log.i(tag, String.format(Locale.ROOT, "RESULT threads=%d/%d ask ms=%.0f chars=%d", t, fixedBatch ?: t, askMs, answer.length))

            // The prefix tree is the flat scoring read in two pieces.
            val block = "\n\nZONE letterhead. HINT: the sender's name and logo.\nZONE address-field. HINT: the window address, who the letter is for.\n"
            val head = "Is «Erika Mustermann» (context: printed on the line: Frau Erika Mustermann; line above: Frau; line below: Musterstraße 12)"
            val asks = listOf(" the sender of the letter? Answer:", " the addressee of the letter? Answer:", " a person (not a company)? Answer:", " a company? Answer:")
            suspend fun flat() = (engine.score(asks.map { block + head + it + tail }, "Yes", "No") as PamResult.Success).data
            suspend fun tree(shared: String, cut: Int) =
                (engine.score(asks.map { (block + head + it + tail).substring(cut) }, "Yes", "No", shared) as PamResult.Success).data
            val a = flat()
            val b = flat()
            val treeBlock = tree(block, block.length)
            val treeHead = tree(block + head, (block + head).length)
            val after = flat()
            Log.i(tag, "RESULT threads=$t equivalence flat=$a flatAgain=$b treeAtBlock=$treeBlock treeAtHead=$treeHead flatAfterTrees=$after")
            // Only what a rollback bug would show: the flat scores must not change after trees ran (same batching, so the same numbers).
            for (i in a.indices) assertEquals("flat again, score $i", a[i], b[i], 1e-3)
            for (i in a.indices) assertEquals("after the trees, score $i", a[i], after[i], 1e-3)

            engine.close()
            engine.unload()
        }
    }
}
