package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The 16 test letters through the whole v2 pipeline, with a scripted "oracle" model that answers
 * what the manifest says. This measures everything *except* the model's own judgement: the candidate
 * stage, the layout zones, the grammar's id sets, the verifier, the party logic and the adapter. The
 * device benchmark (workstream A) replaces the oracle with the real model and measures the rest.
 *
 * Prints a scoreboard (also written to build/reports/extraction-v2-scoreboard.md) as the baseline.
 */
class ExtractionBenchmarkTest {

    private val pipeline = ExtractionV2Pipeline()

    private class Score(
        val id: String,
        val typeOk: Boolean,
        val expected: Int,
        val inCandidates: Int,
        val correct: Int,
        val roleChecks: Map<String, Boolean>,
        val missing: List<String>,
        val wrong: List<String>,
        val foundOnly: Int,
        val noModelCoverage: Int,
    ) {
        val rolesOk: Int get() = roleChecks.values.count { it }
    }

    private fun fold(s: String) = QuoteVerifier.fold(s).trim()

    private fun score(letter: Letter): Score = kotlinx.coroutines.runBlocking {
        val prepared = Prepared(letter.pages)
        val oracle = Oracle.structured(letter, prepared)
        val model = ScriptedInterpreter(oracle.json, Oracle.text(letter))
        val result = pipeline.run(letter.pages, model, 4096)

        val wrong = mutableListOf<String>()
        var correct = 0
        for (e in letter.slots) {
            val got = result.slots[e.slot]
            val ok = got != null && (got.normalized.filter { !it.isWhitespace() }.lowercase().startsWith(e.norm.filter { !it.isWhitespace() }.lowercase()))
            if (ok) correct++ else if (prepared.find(e.kind, e.norm) != null) wrong += "${e.slot.json}: expected ${e.norm}, got ${got?.normalized}"
        }

        // The no-model baseline: how many expected values are among the values code found at all.
        val bare = pipeline.run(letter.pages, null, 4096)
        val noModelCoverage = letter.slots.count { e ->
            bare.foundValues.any { c ->
                val have = c.normalized.filter { !it.isWhitespace() }.lowercase()
                val want = e.norm.filter { !it.isWhitespace() }.lowercase()
                c.kind in (if (e.kind == CandidateKind.DATE) setOf(CandidateKind.DATE, CandidateKind.DATETIME) else setOf(e.kind)) &&
                    (have == want || (e.kind == CandidateKind.DATE && have.startsWith(want)))
            }
        }

        Score(
            id = letter.id,
            typeOk = result.documentType == letter.type,
            expected = letter.slots.size,
            inCandidates = letter.slots.size - oracle.missing.count { m -> letter.slots.any { m.startsWith(it.slot.json + "=") } },
            correct = correct,
            roleChecks = roleChecks(letter, result),
            missing = oracle.missing,
            wrong = wrong,
            foundOnly = bare.foundValues.size,
            noModelCoverage = noModelCoverage,
        )
    }

    private fun roleChecks(letter: Letter, result: ExtractionV2Result): Map<String, Boolean> {
        val m = letter.manifest
        val p = result.parties
        fun names(list: List<Party>) = list.map { fold(it.name) }.toSet()
        val checks = linkedMapOf<String, Boolean>()
        checks["sender"] = p.sender?.let { fold(it.name) == fold(m.sender) } == true
        checks["addressees"] = names(p.allAddressees) == m.addressees.map { fold(it) }.toSet()
        checks["co-addressees"] = names(p.coAddressees) == m.coAddressees.map { fold(it) }.toSet()
        val routing = (p.routingPerson ?: p.careOf)?.name
        checks["routing/c-o"] = (routing?.let { fold(it) }) == m.routing?.let { fold(it) }
        checks["subject persons"] = names(p.subjectPersons) == m.subjectPersons.map { fold(it) }.toSet()
        checks["household"] = p.household == m.household
        checks["sender != addressee"] = p.sender == null || p.allAddressees.none { fold(it.name) == fold(p.sender!!.name) }
        return checks
    }

    private val scores: List<Score> by lazy { Letters.all.map { score(it) } }

    private fun scoreboard(): String = buildString {
        appendLine("# Extraction v2 scoreboard (oracle model = manifest answers)")
        appendLine()
        appendLine("Oracle: a scripted model that answers what the manifest says, through the real grammar ids, verifier and adapter.")
        appendLine("It measures candidates, layout zones, verification and roles; not the model's own judgement (device benchmark, workstream A).")
        appendLine()
        appendLine("| letter | type | fields correct | in candidates | roles correct | no-model: found values | no-model: expected among found |")
        appendLine("|---|---|---|---|---|---|---|")
        for (s in scores) {
            appendLine(
                "| ${s.id} | ${if (s.typeOk) "ok" else "WRONG"} | ${s.correct}/${s.expected} | ${s.inCandidates}/${s.expected} | " +
                    "${s.rolesOk}/${s.roleChecks.size} | ${s.foundOnly} | ${s.noModelCoverage}/${s.expected} |",
            )
        }
        val expected = scores.sumOf { it.expected }
        val correct = scores.sumOf { it.correct }
        val inC = scores.sumOf { it.inCandidates }
        val roles = scores.sumOf { it.rolesOk }
        val roleTotal = scores.sumOf { it.roleChecks.size }
        val covered = scores.sumOf { it.noModelCoverage }
        appendLine("| **total** | ${scores.count { it.typeOk }}/${scores.size} | **$correct/$expected** | $inC/$expected | **$roles/$roleTotal** | | $covered/$expected |")
        appendLine()
        appendLine("Fields correct = slot value equals the manifest value, through the model path.")
        appendLine("No-model: without a model the result assigns nothing (found values only, marked for review); the last column is the ceiling.")
        val gaps = scores.filter { it.missing.isNotEmpty() || it.wrong.isNotEmpty() || it.roleChecks.values.any { ok -> !ok } }
        if (gaps.isNotEmpty()) {
            appendLine()
            appendLine("## Gaps")
            for (s in gaps) {
                if (s.missing.isNotEmpty()) appendLine("- ${s.id}: not among the candidates: ${s.missing}")
                if (s.wrong.isNotEmpty()) appendLine("- ${s.id}: wrong: ${s.wrong}")
                val bad = s.roleChecks.filterValues { !it }.keys
                if (bad.isNotEmpty()) appendLine("- ${s.id}: roles failing: $bad")
            }
        }
    }

    @Test
    fun `scoreboard is printed and the pipeline is right whenever the candidate stage found the value`() {
        val text = scoreboard()
        println(text)
        File("build/reports").mkdirs()
        File("build/reports/extraction-v2-scoreboard.md").writeText(text)

        // Plumbing: a value that was among the candidates must come out as the model chose it.
        for (s in scores) assertThat(s.wrong).isEmpty()
        // The oracle's type is always taken over.
        for (s in scores) assertThat(s.typeOk).isTrue()
    }

    @Test
    fun `roles are right on all ten letters with manifest roles`() {
        for (s in scores.filter { it.id.startsWith("N") }) {
            val failing = s.roleChecks.filterValues { !it }.keys
            assertThat(failing).isEmpty()
        }
    }

    @Test
    fun `the sender is never the addressee, on any letter`() {
        for (letter in Letters.all) {
            val prepared = Prepared(letter.pages)
            val result = kotlinx.coroutines.runBlocking {
                pipeline.run(letter.pages, ScriptedInterpreter(Oracle.structured(letter, prepared).json, Oracle.text(letter)), 4096)
            }
            val sender = result.parties.sender
            for (a in result.parties.allAddressees) {
                assertThat(sender?.name?.let { fold(it) }).isNotEqualTo(fold(a.name))
            }
        }
    }

    @Test
    fun `N8 the information letter is typed as an official letter`() = runTest {
        val letter = Letters.n8
        val prepared = Prepared(letter.pages)
        val result = pipeline.run(letter.pages, ScriptedInterpreter(Oracle.structured(letter, prepared).json, Oracle.text(letter)), 4096)
        assertThat(result.documentType).isEqualTo(ExtractionSchema.OFFICIAL_LETTER)
    }

    @Test
    fun `measured sizes of the prompts and answers for a one page and a three page letter`() = runTest {
        val out = StringBuilder("# Measured sizes (characters; tokens estimated at 2.5 chars per token)\n\n")
        out.appendLine("| letter | pages | call 1 prompt | call 1 answer | call 2 prompt | call 2 answer | grammar |")
        out.appendLine("|---|---|---|---|---|---|---|")
        for (letter in listOf(Letters.n1, Letters.n6, Letters.english)) {
            val prepared = Prepared(letter.pages)
            val oracle = Oracle.structured(letter, prepared)
            val model = ScriptedInterpreter(oracle.json, Oracle.text(letter))
            val result = pipeline.run(letter.pages, model, 4096)
            val call1Prompt = SelectionPrompt.system(ExtractionSchema.DEFAULT, true).length +
                SelectionPrompt.user(model.lastRequest!!.layoutText, prepared.offered).length
            val call2Prompt = SelectionPrompt.TEXT_SYSTEM.length + SelectionPrompt.textUser(model.lastTextRequest!!.layoutText, letter.type.id).length
            val answer1 = oracle.json.length
            val answer2 = Oracle.text(letter).length
            fun tok(c: Int) = (c / 2.5).toInt()
            out.appendLine(
                "| ${letter.id} | ${letter.pages.size} | $call1Prompt (~${tok(call1Prompt)} tok) | $answer1 (~${tok(answer1)} tok) | " +
                    "$call2Prompt (~${tok(call2Prompt)} tok) | $answer2 (~${tok(answer2)} tok) | ${result.diagnostics.grammar?.length} |",
            )
            // Call 1's answer must fit its token budget with room to spare.
            assertThat(tok(answer1)).isLessThan(ModelDocumentInterpreter.MAX_ANSWER_TOKENS)
        }
        println(out)
        File("build/reports").mkdirs()
        File("build/reports/extraction-v2-sizes.md").writeText(out.toString())
    }
}
