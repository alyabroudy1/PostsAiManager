package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.CandidateSet
import com.postsaimanager.core.domain.extraction.candidates.DateValidator
import com.postsaimanager.core.domain.extraction.candidates.IbanValidator
import com.postsaimanager.core.domain.extraction.candidates.Money
import com.postsaimanager.core.domain.extraction.candidates.Validation
import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.v2.CandidateSource
import com.postsaimanager.core.model.OcrBlock
import java.time.LocalDate
import java.time.LocalTime

/** What kind of value an entity extractor found. */
enum class EntityType { DATE_TIME, MONEY, IBAN, PHONE, EMAIL, ADDRESS }

/**
 * One typed span an entity extractor (ML Kit Entity Extraction) found in the letter's text, in the terms the candidates use.
 *
 * @property line the index of the line it sits on, into [GemmaLetterBuilder.linesOf] of the letter's layout
 * @property text the span as printed
 * @property date the calendar date of a [EntityType.DATE_TIME] span, [time] its time of day when it has one
 * @property cents the amount of a [EntityType.MONEY] span in hundredths of [currency] (an ISO code or the sign as printed)
 */
data class EntitySpan(
    val type: EntityType,
    val line: Int,
    val text: String,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val cents: Long? = null,
    val currency: String? = null,
)

/**
 * The port of an entity extractor over the letter's lines.
 *
 * ML Kit downloads its language models on demand, so the answer may not be there yet: null then, and the reading uses only the
 * candidates code finds by shape (and the download is started for the next letter). An answer is never "no entities" by accident.
 */
interface EntityAnnotator {

    /** The spans found in [lines] (one text per line, in order), or null when the extractor's models are not available yet. */
    suspend fun annotate(lines: List<String>): List<EntitySpan>?

    companion object {
        /** No extractor: the reading uses the shape candidates only. */
        val NONE = object : EntityAnnotator {
            override suspend fun annotate(lines: List<String>): List<EntitySpan>? = null
        }
    }
}

/**
 * Merges the entity extractor's spans into the candidates code found by shape: a span the shape finders already hold (the same date,
 * amount, account, number, address) adds nothing; a span they missed becomes a candidate of its own, with an id of its own (`K`, then the
 * kind's letters and a number) so no recorded id of the shape candidates changes. ML Kit only adds; it never removes or re-types a
 * candidate, and what a value means stays the reader's decision.
 *
 * Address spans are no candidates (nothing in the schema takes one): the lines they cover are tagged for the reader as context.
 */
object EntityCandidateMerger {

    /** The merged candidates, and the indexes of the lines ML Kit found an address in. */
    class Merged(val set: CandidateSet, val addressLines: Set<Int>, val added: Int)

    fun merge(base: CandidateSet, spans: List<EntitySpan>, lines: List<LayoutLine>): Merged {
        val added = ArrayList<Candidate>()
        val counters = HashMap<String, Int>()
        fun nextId(prefix: String): String = "$prefix${counters.merge(prefix, 1, Int::plus)}"
        val have = base.candidates

        for (s in spans) {
            val line = lines.getOrNull(s.line) ?: continue
            fun make(kind: CandidateKind, prefix: String, normalized: String, validation: Validation, attrs: Map<String, String> = emptyMap()) {
                added += Candidate(
                    id = nextId(prefix), kind = kind, raw = s.text.trim(), normalized = normalized, page = line.page, bbox = line.bounds,
                    evidence = line.text, validation = validation, attrs = attrs + (SOURCE to SOURCE_MLKIT),
                )
            }
            when (s.type) {
                EntityType.DATE_TIME -> {
                    val date = s.date ?: continue
                    if (TIME_ONLY.matches(s.text) || have.any { isDate(it) && it.normalized.take(DATE_CHARS) == date.toString() } || added.any { isDate(it) && it.normalized.take(DATE_CHARS) == date.toString() }) continue
                    val validation = DateValidator.validate(date.year, date.monthValue, date.dayOfMonth, base.letterDate)
                    if (s.time == null) {
                        make(CandidateKind.DATE, "KD", date.toString(), validation)
                    } else {
                        make(CandidateKind.DATETIME, "KDT", "${date}T${s.time}", validation)
                    }
                }
                EntityType.MONEY -> {
                    val cents = s.cents?.takeIf { it > 0 } ?: continue
                    val currency = s.currency ?: continue
                    if ((have + added).any { it.kind == CandidateKind.AMOUNT && it.cents == cents && it.currency == currency }) continue
                    make(
                        CandidateKind.AMOUNT, "KA", Money(cents, currency, true).canonical(), Validation.Unchecked,
                        mapOf("cents" to cents.toString(), "currency" to currency, "currencyExplicit" to "true"),
                    )
                }
                EntityType.IBAN -> {
                    val compact = IbanValidator.compact(s.text)
                    if ((have + added).any { it.kind == CandidateKind.IBAN && it.normalized == compact }) continue
                    make(CandidateKind.IBAN, "KI", compact, IbanValidator.validate(compact))
                }
                EntityType.PHONE -> {
                    val digits = s.text.filter { it.isDigit() }
                    if ((have + added).any { it.kind == CandidateKind.PHONE && it.normalized.filter(Char::isDigit) == digits }) continue
                    make(
                        CandidateKind.PHONE, "KT", s.text.trim().replace(Regex("\\s+"), " "),
                        if (digits.length in PHONE_DIGITS) Validation.Valid else Validation.Invalid("${digits.length} digits"),
                    )
                }
                EntityType.EMAIL -> {
                    val email = s.text.trim().lowercase()
                    if ((have + added).any { it.kind == CandidateKind.EMAIL && it.normalized == email }) continue
                    make(CandidateKind.EMAIL, "KE", email, Validation.Valid)
                }
                EntityType.ADDRESS -> Unit
            }
        }
        val addressLines = spans.filter { it.type == EntityType.ADDRESS }.map { it.line }.toSet()
        return Merged(CandidateSet(base.candidates + added, base.letterDate), addressLines, added.size)
    }

    private fun isDate(c: Candidate) = c.kind == CandidateKind.DATE || c.kind == CandidateKind.DATETIME

    /** A time of day alone ("10:30", "10.30 h"): ML Kit dates it to today, which the letter never said. */
    private val TIME_ONLY = Regex("\\s*\\d{1,2}[:.]\\d{2}(\\s*\\p{L}{1,4}\\.?)?\\s*")

    const val SOURCE = "source"
    const val SOURCE_MLKIT = "mlkit"
    private const val DATE_CHARS = 10
    private val PHONE_DIGITS = 6..15
}

/**
 * The pipeline's candidate source with the entity extractor's spans merged in ([EntityCandidateMerger]). The spans are found before the
 * pipeline runs (the extractor is asynchronous, this port is not), against the lines of the same layout.
 */
class MergedCandidateSource(
    private val base: CandidateSource,
    private val spans: List<EntitySpan>,
) : CandidateSource {

    /** What the last [find] merged: how many candidates ML Kit added, and the lines it found an address in. */
    var lastMerged: EntityCandidateMerger.Merged? = null
        private set

    override fun find(pages: List<List<OcrBlock>>, layout: LetterLayout): CandidateSet {
        val merged = EntityCandidateMerger.merge(base.find(pages, layout), spans, GemmaLetterBuilder.linesOf(layout))
        lastMerged = merged
        return merged.set
    }
}
