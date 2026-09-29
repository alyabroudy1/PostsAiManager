package com.postsaimanager.core.domain.extraction.v2

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A developer tool, not a check: with the environment variable `GRAMMAR_BENCH_DIR` set it writes,
 * for each benchmark letter, the call-1 system prompt, user message, grammar and the oracle's answer
 * as plain files, which the out-of-tree llama.cpp benchmark program (grammar-constrained decoding
 * speed on a Mac) reads. Without the variable it does nothing.
 */
class GrammarBenchDumpTest {

    @Test
    fun `dump call 1 prompt, grammar and oracle answer for the grammar benchmark`() {
        val dir = System.getenv("GRAMMAR_BENCH_DIR")
        assumeTrue(!dir.isNullOrBlank())
        val out = File(dir!!).apply { mkdirs() }
        for (letter in listOf(Letters.n1, Letters.n6, Letters.invoice)) {
            val prepared = Prepared(letter.pages)
            val oracle = Oracle.structured(letter, prepared)
            val model = ScriptedInterpreter(oracle.json, Oracle.text(letter))
            kotlinx.coroutines.runBlocking { ExtractionV2Pipeline().run(letter.pages, model, 4096) }
            val request = model.lastRequest!!
            File(out, "${letter.id}.system.txt").writeText(SelectionPrompt.system(ExtractionSchema.DEFAULT, true))
            File(out, "${letter.id}.user.txt").writeText(SelectionPrompt.user(request.layoutText, request.offered))
            File(out, "${letter.id}.grammar.txt").writeText(StructuredGrammar.build(request.offered, ExtractionSchema.DEFAULT))
            File(out, "${letter.id}.answer.txt").writeText(oracle.json)
        }
    }
}
