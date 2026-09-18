package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.RecognisedEntity
import com.postsaimanager.core.model.RecognisedFact
import java.text.Normalizer

/**
 * Confidence, computed from evidence instead of asked for.
 *
 * ### Why the model's own number is discarded, not blended
 *
 * On both models tried on device (Gemma 4 E2B, Qwen3.5 2B) the `confidence` field the model
 * emits is 0.9 for essentially every entity and every fact, regardless of how the system
 * prompt spells out confidence bands. That is not a noisy signal — it is a constant a small
 * model produces because "0.9" is a plausible-looking token to follow a claim, not because it
 * measured anything. Averaging a constant with a real signal (`(0.9 + derived) / 2`) does not
 * make the result safer; it just compresses the derived signal's range towards 0.9, which is
 * strictly worse than ignoring the constant outright. So this object's output *replaces* the
 * model's number in [AiExtractionUseCase.sanitise] rather than combining with it.
 *
 * ### What this catches, and what it does not
 *
 * The dominant term is **grounding**: is the claimed value actually present in the page text
 * the model was reading? A model that hallucinates a value (wrong year, wrong number) is
 * writing something that simply is not on the page, and a substring check catches that for
 * free without needing to understand what the value means.
 *
 * Grounding is modulated by **format validity** for [FactKind]s that have a recognisable
 * shape. This catches a specific and different failure: a value that is genuinely on the
 * page but has been given the wrong *kind* — a phone number classified as an IBAN. The text
 * is real, so grounding alone would call it confident; the shape check is what flags that the
 * classification, not the reading, is wrong.
 *
 * What this deliberately does **not** catch: a *role* error, such as tagging the sender's own
 * organisation as [com.postsaimanager.core.model.EntityRole.MENTIONED] instead of `SENDER`.
 * The entity's name is correct and grounded; only the role claim is wrong, and nothing here
 * inspects roles against the page layout. That failure needs a different check (e.g. cross
 * referencing [DocumentLayout] zones against the claimed role) which is out of scope here.
 * Recorded honestly rather than left to look like this gives blanket coverage.
 */
object ExtractionConfidence {

    fun forFact(fact: RecognisedFact, pageText: String): Float {
        val grounding = groundingScore(fact.value, pageText)
        // Nothing on the page supports the value at all — the strongest signal there is,
        // and worth returning as-is rather than letting a format check pull it back up.
        if (grounding <= NOT_GROUNDED) return grounding

        // Grounded but the wrong shape for its claimed kind: real text, wrong label. Lower
        // than plain "not grounded" would suggest confusion about what it copied, but this is
        // worse — a deliberate-looking classification that is simply incorrect.
        return if (formatValid(fact.value, fact.kind)) grounding else FORMAT_INVALID
    }

    fun forEntity(entity: RecognisedEntity, pageText: String): Float {
        // A name that is only a title has no person in it at all. Grounding would score this
        // high — "Frau" is right there on the page — which is exactly the earlier regex
        // extractor's bug (see AiExtractionUseCase's KDoc). Checked before grounding so
        // grounding never gets a chance to launder it.
        if (isSalutationOnly(entity.name)) return NOT_GROUNDED
        return groundingScore(entity.name, pageText)
    }

    // ---- Grounding bands -----------------------------------------------------------------
    //
    // Four tiers, deliberately spread wide: the whole point of deriving confidence is that it
    // varies enough to sort a "needs review" list from a "trust it" list. A narrow band would
    // reproduce the same useless-constant problem this replaces, just with a different number.

    /** The value appears character-for-character on the page. As strong as evidence gets. */
    private const val GROUNDED_EXACT = 0.95f

    /**
     * Appears once obvious noise is normalised away: case, whitespace runs, and common German
     * OCR/typing variants of diacritics (`ü`/`ue`, `ß`/`ss`, stray accents). Still a direct
     * copy, just not a byte-identical one.
     */
    private const val GROUNDED_NORMALISED = 0.85f

    /**
     * Every significant word in the value turns up somewhere on the page, but not as one
     * contiguous run — a paraphrase, a reordering, or values split across two blocks that
     * [DocumentLayout] presented adjacently. Real, but weaker than a direct copy, and this is
     * deliberately below [com.postsaimanager.core.model.DocumentUnderstanding.AUTO_LINK_CONFIDENCE]
     * so it lands in review rather than being auto-linked.
     */
    private const val GROUNDED_TOKENS = 0.5f

    /**
     * Nothing on the page supports this. This is the band the `31.01.2066` hallucination
     * (page says `31.01.2026`) must land in — the model produced a string the source document
     * never contained.
     */
    private const val NOT_GROUNDED = 0.05f

    /**
     * Grounded, but the wrong shape for the kind claimed — the `030 12345678` phone number
     * tagged as an IBAN. Between [GROUNDED_TOKENS] and [NOT_GROUNDED] would suggest this is a
     * middling reading; it is not a reading problem at all, so it gets its own low band.
     */
    private const val FORMAT_INVALID = 0.15f

    /** Below this length a token is noise (articles, single letters) unless it is a number. */
    private const val MIN_SIGNIFICANT_TOKEN_LENGTH = 3

    private val WHITESPACE = Regex("\\s+")
    private val DIACRITIC_MARK = Regex("\\p{Mn}+")
    private val TOKEN = Regex("[\\p{L}\\p{Nd}]+")

    private val SALUTATIONS_ONLY = setOf(
        "frau", "herr", "hr", "fr", "familie",
        "sehr geehrte", "sehr geehrter", "sehr geehrte damen und herren",
        "mr", "mrs", "ms", "miss", "dear",
    )

    private fun groundingScore(value: String, pageText: String): Float {
        val needle = value.trim()
        if (needle.isEmpty() || pageText.isBlank()) return NOT_GROUNDED

        if (pageText.contains(needle)) return GROUNDED_EXACT

        val normalisedNeedle = normalise(needle)
        val normalisedPage = normalise(pageText)
        if (normalisedNeedle.isNotEmpty() && normalisedPage.contains(normalisedNeedle)) {
            return GROUNDED_NORMALISED
        }

        val foldedNeedle = fold(needle)
        val foldedPage = fold(pageText)
        if (foldedNeedle.isNotEmpty() && foldedPage.contains(foldedNeedle)) {
            return GROUNDED_NORMALISED
        }

        val needleTokens = significantTokens(needle)
        if (needleTokens.isEmpty()) return NOT_GROUNDED
        val pageTokens = significantTokens(pageText).toSet()
        return if (needleTokens.all { it in pageTokens }) GROUNDED_TOKENS else NOT_GROUNDED
    }

    /** Lowercase, whitespace-collapsed. The baseline every other comparison builds on. */
    private fun normalise(text: String): String =
        text.lowercase().replace(WHITESPACE, " ").trim()

    /**
     * [normalise] plus German special characters folded to their unaccented spelling, and a
     * general Unicode accent strip for anything else (names borrow letters from other
     * languages too).
     *
     * This tolerates the OCR *substituting* one spelling of a letter for another — "Strasse"
     * for "Straße", a missing umlaut dot. It does **not** tolerate OCR *inserting or dropping*
     * characters — an observed device run had OCR read "Müllerstraße" as "Müllerstralße" (an
     * extra "l"), which still fails every tier here and falls through to the token check or
     * to "not grounded". Catching that needs edit-distance fuzzy matching, which is
     * deliberately not attempted: exact/normalised/token-subset/absent is enough resolution
     * for what this score is used for, and a fuzzier matcher would start forgiving genuine
     * hallucinations along with genuine OCR noise.
     */
    private fun fold(text: String): String {
        val folded = normalise(text)
            .replace("ß", "ss")
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
        val decomposed = Normalizer.normalize(folded, Normalizer.Form.NFD)
        return DIACRITIC_MARK.replace(decomposed, "")
    }

    private fun significantTokens(text: String): List<String> =
        TOKEN.findAll(text)
            .map { fold(it.value) }
            .filter { it.length >= MIN_SIGNIFICANT_TOKEN_LENGTH || it.any(Char::isDigit) }
            .toList()

    private fun isSalutationOnly(name: String): Boolean =
        normalise(name).trimEnd('.', ',') in SALUTATIONS_ONLY

    /**
     * Whether a fact's value has the shape its claimed [FactKind] implies. A value can be
     * perfectly grounded and still be the wrong kind of thing — that is what modulates
     * [forFact] down to [FORMAT_INVALID] rather than being folded into grounding itself.
     */
    private fun formatValid(value: String, kind: FactKind): Boolean = when (kind) {
        FactKind.DATE, FactKind.DEADLINE -> DATE_SHAPE.containsMatchIn(value)
        // Two letters (country code), two digits (check digits), then the account identifier.
        FactKind.IBAN -> IBAN_SHAPE.matches(value.replace(" ", ""))
        FactKind.AMOUNT -> AMOUNT_SHAPE.containsMatchIn(value)
        FactKind.REFERENCE -> value.any(Char::isDigit)
        FactKind.SUBJECT, FactKind.OTHER -> true
    }

    private val DATE_SHAPE = Regex("""\b\d{1,2}[./]\d{1,2}[./]\d{2,4}\b|\b\d{4}-\d{2}-\d{2}\b""")
    private val IBAN_SHAPE = Regex("^[A-Za-z]{2}\\d{2}[A-Za-z0-9]+$")
    private val AMOUNT_SHAPE = Regex("""\d+[.,]\d{2}\b|\d[\d.,]*\s*(€|EUR|Euro)""")
}
