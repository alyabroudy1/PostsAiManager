package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.candidates.BlockKey
import com.postsaimanager.core.domain.extraction.candidates.BlockZone
import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateExtractor
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.CandidateSet
import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import java.io.File
import java.util.Locale

/** One fact the manifest says is on the letter, reduced to what the deterministic stage can be judged on. */
data class Expectation(
    val doc: String,
    val field: String,
    val kind: CandidateKind,
    /** Normalised like [Candidate.normalized]: ISO date, "1284.50 EUR", compact IBAN, reference, "P14D". */
    val norm: String,
    val page: Int,
    /** The value as a reader sees it, to check whether OCR got it at all. */
    val rawToken: String,
)

enum class MissCause(val text: String) {
    /** The printed value is not in the OCR text of its page (misread, split beyond repair, not read at all). */
    NOT_IN_OCR("value not in OCR text"),

    /** The value is in the OCR text but no candidate was proposed. */
    NOT_EXTRACTED("in OCR text, no candidate"),

    /** A candidate with the right value exists, but on another page. */
    WRONG_PAGE("right value on other page"),
}

data class FactResult(val exp: Expectation, val found: Boolean, val cause: MissCause?, val candidate: Candidate?)

data class DocResult(
    val key: String,
    val web: Boolean,
    val pages: Int,
    val blocks: Int,
    val candidates: Int,
    val facts: List<FactResult>,
    val noiseHits: List<String>,
    val extrasByKind: Map<CandidateKind, Int>,
    val addresseeInAddressField: Boolean?,
    val senderInSenderZones: Boolean?,
    val senderLeaksIntoAddressField: Boolean?,
    val layout: LetterLayout,
    val candidateSet: CandidateSet,
)

class BenchmarkReport(val docs: List<DocResult>) {
    private fun invented() = docs.filter { !it.web }

    /** Metric name to value, over the invented letters only (the gate's input). Rates are 0..1. */
    val metrics: Map<String, Double> get() = metricsOf(invented())

    /** Same metrics over the web samples, when present locally. Never gated. */
    val webMetrics: Map<String, Double> get() = docs.filter { it.web }.takeIf { it.isNotEmpty() }?.let(::metricsOf).orEmpty()

    fun metricsOf(ds: List<DocResult>): Map<String, Double> {
        val out = linkedMapOf<String, Double>()
        val facts = ds.flatMap { it.facts }
        fun rate(fs: List<FactResult>) = if (fs.isEmpty()) 1.0 else fs.count { it.found }.toDouble() / fs.size
        out["recall.all"] = rate(facts)
        for (k in CandidateKind.entries) {
            val fs = facts.filter { it.exp.kind == k }
            if (fs.isNotEmpty()) out["recall.$k"] = rate(fs)
        }
        out["noise.hits"] = ds.sumOf { it.noiseHits.size }.toDouble()
        val a = ds.mapNotNull { it.addresseeInAddressField }
        out["zone.addressee"] = if (a.isEmpty()) 1.0 else a.count { it }.toDouble() / a.size
        val s = ds.mapNotNull { it.senderInSenderZones }
        out["zone.sender"] = if (s.isEmpty()) 1.0 else s.count { it }.toDouble() / s.size
        val l = ds.mapNotNull { it.senderLeaksIntoAddressField }
        out["leak.senderInAddress"] = l.count { it }.toDouble()
        return out
    }
}

object ExtractionBenchmark {

    fun run(docs: List<Pair<ManifestDoc, Fixture>> = BenchmarkFixtures.load().docs): BenchmarkReport =
        BenchmarkReport(docs.map { (m, f) -> score(m, f) })

    fun score(m: ManifestDoc, f: Fixture): DocResult {
        val pageBlocks = f.pages.map { it.blocks }
        // Same deterministic chain the pipeline runs: layout zones first, candidates second (names use the zones).
        val layout = LetterLayoutAnalyzer.analyze(pageBlocks)
        val zones = blockZones(layout, pageBlocks)
        val set = CandidateExtractor.extract(pageBlocks, zones)

        val expectations = Expectations.of(m)
        val pageText = f.pages.associate { p -> p.pageNumber to squash(p.blocks.joinToString("\n") { it.text }) }
        val facts = expectations.map { e ->
            val hit = set.candidates.firstOrNull { it.page == e.page && Expectations.matches(it, e) }
            if (hit != null) {
                FactResult(e, true, null, hit)
            } else {
                val other = set.candidates.any { Expectations.matches(it, e) }
                val inOcr = pageText[e.page]?.contains(squash(e.rawToken)) == true
                FactResult(e, false, if (other) MissCause.WRONG_PAGE else if (inOcr) MissCause.NOT_EXTRACTED else MissCause.NOT_IN_OCR, null)
            }
        }

        val noiseDigits = m.notFacts?.let { Regex("\\b\\d{5,}\\b").findAll(it).map { r -> r.value }.toList() }.orEmpty()
        val noise = if (noiseDigits.isEmpty()) {
            emptyList()
        } else {
            set.candidates
                .filter { it.kind !in setOf(CandidateKind.DATE, CandidateKind.DATETIME, CandidateKind.AMOUNT) }
                .filter { c -> noiseDigits.any { d -> squash(c.normalized).contains(d) || squash(c.raw).contains(d) } }
                .map { "${it.id} ${it.kind} '${it.raw}'" }
        }

        val matchedIds = facts.mapNotNull { it.candidate?.id }.toSet()
        val extras = set.candidates.filter { it.id !in matchedIds }.groupingBy { it.kind }.eachCount()

        val page1 = layout.page(1)
        val addressText = squash(page1?.zoneText(LetterZone.ADDRESS_FIELD).orEmpty().joinToString(" "))
        val addressees = m.roles.addressees.map { it.substringBefore(',') }.filter { it.isNotBlank() }
        // Every name token must sit in the address field: OCR reorders lines ("Adam Mustermann" above
        // "Erziehungsberechtigte von") and merges names ("Max und Erika Mustermann").
        val addressTokens = page1?.zoneText(LetterZone.ADDRESS_FIELD).orEmpty().joinToString(" ").split(Regex("\\s+")).map(::squash).toSet()
        val addresseeOk = if (addressees.isEmpty()) null else addressees.all { a ->
            a.split(Regex("\\s+")).map(::squash).filter { it.length > 2 }.all { it in addressTokens }
        }

        val senderName = m.senderName?.substringBefore(',')?.takeIf { it.isNotBlank() }
        val senderZoneText = layout.pages.flatMap { p ->
            listOf(LetterZone.LETTERHEAD, LetterZone.RETURN_ADDRESS_LINE, LetterZone.FOOTER).flatMap { p.zoneText(it) }
        }.joinToString(" ").let(::squash)
        val senderOk = senderName?.let { senderZoneText.contains(squash(it)) }
        val leaks = senderName?.let { n ->
            val sq = squash(n)
            addressText.contains(sq) && addressees.none { squash(it).contains(sq) }
        }

        return DocResult(
            m.key, m.web, f.pages.size, pageBlocks.sumOf { it.size }, set.candidates.size, facts,
            noise, extras, addresseeOk, senderOk, leaks, layout, set,
        )
    }

    /**
     * Maps the layout stage's per-line zones back onto OCR blocks (the extractor wants block keys).
     * A block gets the zone most of its lines were given; ties prefer the address field.
     */
    fun blockZones(layout: LetterLayout, pageBlocks: List<List<com.postsaimanager.core.model.OcrBlock>>): Map<BlockKey, BlockZone> {
        val out = HashMap<BlockKey, BlockZone>()
        pageBlocks.forEachIndexed { p, blocks ->
            val lines: List<LayoutLine> = layout.page(p + 1)?.lines.orEmpty().filter { !it.isNoise }
            val byText = lines.groupBy { squash(it.text) }
            blocks.forEachIndexed { i, b ->
                val zs = b.text.split('\n').mapNotNull { l -> byText[squash(l)]?.firstOrNull()?.zone }
                val mapped = zs.mapNotNull {
                    when (it) {
                        LetterZone.ADDRESS_FIELD -> BlockZone.ADDRESS_FIELD
                        LetterZone.LETTERHEAD -> BlockZone.LETTERHEAD
                        LetterZone.RETURN_ADDRESS_LINE -> BlockZone.RETURN_ADDRESS
                        else -> null
                    }
                }
                if (mapped.isNotEmpty() && mapped.size * 2 >= zs.size) {
                    val counts = mapped.groupingBy { it }.eachCount()
                    val best = counts.maxOf { it.value }
                    val pick = if (counts[BlockZone.ADDRESS_FIELD] == best) BlockZone.ADDRESS_FIELD else counts.filterValues { it == best }.keys.first()
                    out[BlockKey(p + 1, i)] = pick
                }
            }
        }
        return out
    }

    fun squash(s: String): String = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    // ── Scoreboard ──

    fun scoreboard(report: BenchmarkReport, skipped: List<String>): String {
        val sb = StringBuilder()
        fun pct(v: Double) = String.format(Locale.ROOT, "%.1f%%", v * 100)
        sb.appendLine("# Extraction benchmark (real phone OCR)\n")
        sb.appendLine("Deterministic stages only: LetterLayoutAnalyzer + CandidateExtractor. Reference: 112/112 on generator phrasing (ManifestCoverageTest).\n")
        fun table(title: String, m: Map<String, Double>) {
            if (m.isEmpty()) return
            sb.appendLine("## $title\n\n| metric | value |\n|---|---|")
            m.forEach { (k, v) ->
                sb.appendLine("| $k | ${if (k.startsWith("noise") || k.startsWith("leak")) v.toInt().toString() else pct(v)} |")
            }
            sb.appendLine()
        }
        table("Invented letters (gated)", report.metrics)
        table("Web samples (local only, not gated)", report.webMetrics)

        sb.appendLine("## Per document\n")
        sb.appendLine("| doc | pages | blocks | candidates | facts found | noise | addressee in ADDRESS_FIELD | sender in sender zones |")
        sb.appendLine("|---|---|---|---|---|---|---|---|")
        for (d in report.docs) {
            fun yn(b: Boolean?) = when (b) { true -> "yes"; false -> "NO"; null -> "-" }
            sb.appendLine(
                "| ${d.key}${if (d.web) " (web)" else ""} | ${d.pages} | ${d.blocks} | ${d.candidates} | " +
                    "${d.facts.count { it.found }}/${d.facts.size} | ${d.noiseHits.size} | ${yn(d.addresseeInAddressField)} | ${yn(d.senderInSenderZones)} |",
            )
        }
        sb.appendLine()

        val misses = report.docs.flatMap { d -> d.facts.filter { !it.found }.map { d to it } }
        sb.appendLine("## Missed facts (${misses.size})\n")
        val byCause = misses.groupingBy { it.second.cause }.eachCount()
        sb.appendLine(byCause.entries.joinToString(", ") { "${it.key?.text}: ${it.value}" } + "\n")
        sb.appendLine("| doc | page | field | kind | expected | cause |\n|---|---|---|---|---|---|")
        for ((d, f) in misses) {
            sb.appendLine("| ${d.key}${if (d.web) " (web)" else ""} | ${f.exp.page} | ${f.exp.field} | ${f.exp.kind} | ${f.exp.norm} | ${f.cause?.text} |")
        }
        sb.appendLine()

        val zoneFails = report.docs.filter { it.addresseeInAddressField == false || it.senderLeaksIntoAddressField == true }
        if (zoneFails.isNotEmpty()) {
            sb.appendLine("## Zone failures (page-1 ADDRESS_FIELD lines; LETTERHEAD/RETURN lines)\n")
            for (d in zoneFails) {
                val p1 = d.layout.page(1)
                sb.appendLine("- ${d.key}: address=${p1?.zoneText(LetterZone.ADDRESS_FIELD)}; " +
                    "letterhead=${p1?.zoneText(LetterZone.LETTERHEAD)}; return=${p1?.zoneText(LetterZone.RETURN_ADDRESS_LINE)}")
            }
            sb.appendLine()
        }

        val noise = report.docs.filter { it.noiseHits.isNotEmpty() }
        if (noise.isNotEmpty()) {
            sb.appendLine("## Noise (not_facts proposed as candidates)\n")
            noise.forEach { d -> d.noiseHits.forEach { sb.appendLine("- ${d.key}: $it") } }
            sb.appendLine()
        }
        sb.appendLine("## Extra candidates (not matched to a manifest fact), per kind\n")
        val extras = report.docs.flatMap { it.extrasByKind.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        sb.appendLine(extras.entries.joinToString(", ") { "${it.key}: ${it.value}" } + "\n")
        if (skipped.isNotEmpty()) sb.appendLine("Skipped (no fixture): ${skipped.joinToString()}")
        return sb.toString()
    }

    fun writeScoreboard(report: BenchmarkReport, skipped: List<String>): File {
        val f = File("build/benchmark/scoreboard.md")
        f.parentFile.mkdirs()
        f.writeText(scoreboard(report, skipped))
        return f
    }
}

/** Turns manifest `expected` entries into checkable [Expectation]s. Values that are free text are not scored. */
object Expectations {

    private val NUM_DATE = Regex("(?<!\\d)(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?!\\d)")
    private val LONG_DATE = Regex(
        "(?<!\\d)(\\d{1,2})\\s+(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(\\d{4})",
    )
    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val TIME = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})\\s*Uhr")
    private val MONEY_DE = Regex("(?<![\\d.,])(\\d{1,3}(?:\\.\\d{3})*|\\d+),(\\d{2})(?!\\d)")
    private val MONEY_GBP = Regex("£\\s?(\\d{1,3}(?:,\\d{3})*|\\d+)\\.(\\d{2})")
    private val IBAN = Regex("[A-Z]{2}\\d{2}(?: ?[0-9A-Z]{4}){3,7}(?: ?[0-9A-Z]{1,4})?")

    private val REFERENCE_FIELDS = setOf(
        "reference_number", "customer_number", "tax_number", "policy_number", "account_number",
        "receipt_number", "contract_account", "meter_number", "matriculation_number",
    )
    private val AMOUNT_PREFIXES = listOf(
        "amount", "net_amount", "vat", "fee", "total_costs", "advance_paid", "previous_amount",
        "new_monthly_advance", "old_premium", "new_premium", "festgesetzte", "Vorauszahlungen",
    )

    fun of(m: ManifestDoc): List<Expectation> {
        val out = mutableListOf<Expectation>()
        for (e in m.expected) {
            val name = e.field.substringBefore(' ').trim()
            val add = { kind: CandidateKind, norm: String, raw: String ->
                out += Expectation(m.key, e.field, kind, norm, e.page, raw)
            }
            when {
                name == "iban" -> IBAN.find(e.value)?.value?.let { add(CandidateKind.IBAN, it.filter { c -> !c.isWhitespace() }, it) }
                name in REFERENCE_FIELDS -> add(CandidateKind.REFERENCE, e.value.trim(), e.value)
                AMOUNT_PREFIXES.any { name.startsWith(it) } -> money(e.value)?.let { (norm, raw) -> add(CandidateKind.AMOUNT, norm, raw) }
                name.contains("date") || name in setOf("deadline", "period", "appointment", "contract_end") ||
                    name.startsWith("due") -> {
                    val dates = dates(e.value)
                    val time = TIME.find(e.value)
                    if (dates.isNotEmpty()) {
                        for ((iso, raw) in dates) {
                            if (time != null && dates.size == 1) {
                                add(CandidateKind.DATETIME, "${iso}T${time.groupValues[1].padStart(2, '0')}:${time.groupValues[2]}", raw)
                            } else {
                                add(CandidateKind.DATE, iso, raw)
                            }
                        }
                    } else if (name == "deadline") {
                        Regex("(\\d+) Tage").find(e.value)?.let { add(CandidateKind.RELATIVE_DEADLINE, "P${it.groupValues[1]}D", it.value) }
                            ?: if (e.value.contains("eines Monats")) add(CandidateKind.RELATIVE_DEADLINE, "P1M", "eines Monats") else Unit
                    }
                }
            }
        }
        return out
    }

    private fun dates(v: String): List<Pair<String, String>> {
        val r = mutableListOf<Pair<Int, Pair<String, String>>>()
        NUM_DATE.findAll(v).forEach {
            val (d, mo, y) = it.destructured
            r += it.range.first to (String.format(Locale.ROOT, "%s-%02d-%02d", y, mo.toInt(), d.toInt()) to it.value)
        }
        LONG_DATE.findAll(v).forEach {
            val (d, mo, y) = it.destructured
            r += it.range.first to (String.format(Locale.ROOT, "%s-%02d-%02d", y, MONTHS.indexOf(mo) + 1, d.toInt()) to it.value)
        }
        return r.sortedBy { it.first }.map { it.second }
    }

    private fun money(v: String): Pair<String, String>? {
        val de = MONEY_DE.find(v)
        val gbp = MONEY_GBP.find(v)
        return when {
            gbp != null && (de == null || gbp.range.first < de.range.first) ->
                "${gbp.groupValues[1].replace(",", "")}.${gbp.groupValues[2]} GBP" to gbp.value
            de != null -> "${de.groupValues[1].replace(".", "")}.${de.groupValues[2]} EUR" to de.value
            else -> null
        }
    }

    fun matches(c: Candidate, e: Expectation): Boolean {
        fun sq(s: String) = ExtractionBenchmark.squash(s)
        return when (e.kind) {
            CandidateKind.DATE -> (c.kind == CandidateKind.DATE || c.kind == CandidateKind.DATETIME) && c.normalized.startsWith(e.norm)
            CandidateKind.DATETIME -> c.kind == CandidateKind.DATETIME && c.normalized == e.norm
            CandidateKind.AMOUNT ->
                c.kind == CandidateKind.AMOUNT && (c.normalized == e.norm || c.normalized.substringBefore(' ') == e.norm.substringBefore(' ') && !e.norm.contains(' '))
            CandidateKind.REFERENCE -> c.kind == CandidateKind.REFERENCE && sq(c.normalized) == sq(e.norm)
            else -> c.kind == e.kind && sq(c.normalized) == sq(e.norm)
        }
    }
}
