package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.AmountParser
import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateExtractor
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.Validation
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import java.time.LocalDate

/**
 * Turns the words of an answer into the typed values the app stores: a date text into a calendar date, an amount text into cents and a
 * currency, a number text into a reference. A value the letter's own candidates already hold (the same date, the same cents, the same
 * characters) is that candidate, so it keeps its place, its page and its box; any other becomes a new candidate with an id of its own
 * ("Q1", "Q2" ...), kept in [synthesized]. Nothing is looked for in the OCR text and nothing is dropped for not being there: the value is
 * the model's reading, which the person confirms.
 *
 * Only what cannot be typed is not made (a date text that is no date, an amount text that is no number): it comes back null.
 */
class QaValueTyper(private val offered: OfferedCandidates) {

    private val made = mutableListOf<Candidate>()
    private val byKey = HashMap<String, Candidate>()

    /** The candidates made for values the letter's own do not hold, in the order they were made. */
    val synthesized: List<Candidate> get() = made.toList()

    fun date(text: String): Candidate? {
        val extracted = CandidateExtractor.extractFromText(text).candidates.firstOrNull { it.kind == CandidateKind.DATE || it.kind == CandidateKind.DATETIME }
        val iso = ISO.find(text)?.value?.takeIf { parses(it) } ?: extracted?.normalized?.take(ISO_CHARS)?.takeIf { parses(it) } ?: return null
        offered.rows.map { it.candidate }
            .firstOrNull { (it.kind == CandidateKind.DATE || it.kind == CandidateKind.DATETIME) && it.normalized.take(ISO_CHARS) == iso }
            ?.let { return it }
        return byKey.getOrPut("date:$iso") {
            make(extracted?.takeIf { it.normalized.take(ISO_CHARS) == iso } ?: candidate(CandidateKind.DATE, text.trim(), iso))
        }
    }

    fun amount(text: String): Candidate? {
        val extracted = CandidateExtractor.extractFromText(text).candidates.firstOrNull { it.kind == CandidateKind.AMOUNT }
        val money = extracted?.cents?.let { cents -> cents to (extracted.currency ?: DEFAULT_CURRENCY) }
            ?: NUMBER.find(text)?.value?.let { AmountParser.parse(it, null) }?.let { it.cents to it.currency }
            ?: return null
        offered.rows.map { it.candidate }.firstOrNull { it.kind == CandidateKind.AMOUNT && it.cents == money.first }?.let { return it }
        return byKey.getOrPut("amount:${money.first}") {
            make(
                extracted?.takeIf { it.cents == money.first }
                    ?: candidate(
                        CandidateKind.AMOUNT, text.trim(), "%d.%02d %s".format(money.first / 100, kotlin.math.abs(money.first % 100), money.second),
                        mapOf("cents" to money.first.toString(), "currency" to money.second),
                    ),
            )
        }
    }

    /** A reference, account or contact value: the letter's candidate with the same characters, else a new one of [kind]. */
    fun reference(text: String, kind: CandidateKind): Candidate {
        val printed = text.trim()
        val key = compact(printed)
        offered.rows.map { it.candidate }.firstOrNull { it.kind in REFERENCE_KINDS && (compact(it.raw) == key || compact(it.normalized) == key) }?.let { return it }
        return byKey.getOrPut("ref:$key") { make(candidate(kind, printed, printed)) }
    }

    private fun make(c: Candidate): Candidate = c.copy(id = "Q${made.size + 1}", page = 1, bbox = null).also { made += it }

    private fun candidate(kind: CandidateKind, raw: String, normalized: String, attrs: Map<String, String> = emptyMap()) = Candidate(
        id = "", kind = kind, raw = raw, normalized = normalized, page = 1, bbox = null, evidence = raw, validation = Validation.Unchecked, attrs = attrs,
    )

    private fun parses(iso: String) = runCatching { LocalDate.parse(iso) }.isSuccess

    private fun compact(s: String) = QuoteVerifier.fold(s).filter { it.isLetterOrDigit() }

    private companion object {
        val ISO = Regex("\\d{4}-\\d{2}-\\d{2}")
        val NUMBER = Regex("\\d[\\d.,]*\\d|\\d")
        const val ISO_CHARS = 10
        const val DEFAULT_CURRENCY = "EUR"
        val REFERENCE_KINDS = setOf(CandidateKind.REFERENCE, CandidateKind.IBAN, CandidateKind.PHONE, CandidateKind.EMAIL, CandidateKind.BIC)
    }
}
