package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.layout.LetterZone

/**
 * A layout class, as data: which zones a page of this kind has, where they sit (geometry only), what
 * to tell the model about each zone, and which questions are asked there.
 *
 * Nothing here reads a word of the letter. A template is chosen from *where things are* (see
 * [TemplateMatcher]), and what it tells the model is a prior ("this block is usually ..."), never a
 * decision: the model may contradict it, and the verifier then caps the answer.
 */

/** A rectangle of the page, normalised 0..1, that a zone's lines are expected to sit in. */
data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    /** The same region seen in a mirror (right-to-left layouts). */
    fun mirrored() = Region(1f - right, top, 1f - left, bottom)
}

/**
 * How a template expects one zone to look. The fit of a page is the share of the zone's lines whose
 * centre is inside [region], and 0 when the zone is absent.
 */
data class ZoneExpectation(val zone: LetterZone, val region: Region, val weight: Float = 1f)

/** A geometric range a whole-page measure has to fall in for a template to fit; the fit falls off linearly outside it. */
data class Range(val min: Float, val max: Float, val softness: Float, val weight: Float = 1f) {
    fun fit(v: Float): Float = when {
        v in min..max -> 1f
        v < min -> (1f - (min - v) / softness).coerceAtLeast(0f)
        else -> (1f - (v - max) / softness).coerceAtLeast(0f)
    }
}

/**
 * What identifies a template, from geometry alone. Every part is optional; the score of a template is
 * the weighted mean of the parts it has.
 *
 * @property fontRatio the page's tallness as measured from the text itself (see [LayoutFeatures.fontRatio]):
 *   about 0.5 to 0.8 on an A4 page, above 1.1 on a narrow receipt roll.
 * @property tableRows how many rows of three or more separate text columns the letter has.
 * @property keyValueShare the share of rows that are exactly "label ... value" pairs in two aligned columns.
 * @property rightToLeft the page reads right to left (true), left to right (false) or either (null).
 */
data class TemplateSignature(
    val zones: List<ZoneExpectation> = emptyList(),
    val fontRatio: Range? = null,
    val tableRows: Range? = null,
    val keyValueShare: Range? = null,
    val rightToLeft: Boolean? = null,
    /** Zones this kind of page does not have: at most this many page-1 lines in the zone (a form has no address window). */
    val absent: Map<LetterZone, Int> = emptyMap(),
)

/** One question of the schema asked at a zone, by the name [QuestionNames] gives it. */
typealias AskName = String

/**
 * What the template says about one zone.
 *
 * @property hint English, for the model: what this block usually is. A prior, never an answer.
 * @property asks the questions asked on this zone's text and candidates (question names, see [QuestionNames]).
 */
data class ZoneSpec(val zone: LetterZone, val hint: String, val asks: List<AskName> = emptyList())

/**
 * Where a convention is used, as data: ISO 3166 country codes and ISO 15924 script codes, compared case-insensitively.
 * Only a tie-break (see [TemplateMatcher.match]): it never makes a template match that the geometry does not.
 * The codes live here, in template data, and nowhere in code.
 */
data class LocaleHint(val countries: Set<String> = emptySet(), val scripts: Set<String> = emptySet()) {
    /** How many of the given [country] and [script] this hint names (0 to 2). */
    fun matches(country: String?, script: String?): Int =
        (if (country != null && countries.any { it.equals(country, ignoreCase = true) }) 1 else 0) +
            (if (script != null && scripts.any { it.equals(script, ignoreCase = true) }) 1 else 0)
}

/**
 * @property id stable name, also told to the model ("LAYOUT: DIN5008_B").
 * @property description one English line for the model's instructions.
 * @property signature the geometry that identifies it.
 * @property zones the zones it expects, with their hints and questions.
 * @property remap zones the analyzer produced that this template reads as another zone (a receipt has no
 *   address field: what the analyzer took for one is an item list). Geometry classes, not keywords.
 * @property placements where a slot is asked, for slots this template places differently from [SlotPlacements.DEFAULT].
 * @property locale where this convention is used; a small tie-break when the address country or script is known, else unused.
 */
data class LayoutTemplate(
    val id: String,
    val description: String,
    val signature: TemplateSignature,
    val zones: List<ZoneSpec>,
    val remap: Map<LetterZone, LetterZone> = emptyMap(),
    val placements: Map<String, List<LetterZone>> = emptyMap(),
    val locale: LocaleHint? = null,
) {
    fun spec(zone: LetterZone): ZoneSpec? = zones.firstOrNull { it.zone == zone }
}

/** The names of the questions a zone can carry: the questionnaire's question names, so recordings line up. */
object QuestionNames {
    const val TYPE = "type"
    const val SENDER = "sender"
    const val ADDRESSEE = "addressee"
    const val SUBJECT_PERSON = "subject_person"
    const val CONTACT = "contact"
    const val CARE_OF = "care_of"
    const val EXTRAS = "extras"

    /**
     * Where the scoring reader looks for extras: every zone that holds facts worth keeping, not only the body (the amounts of a
     * payment block and the numbers of a reference block are extras too). A separate name from [EXTRAS], which is where the
     * generating reader asks its one extras question on a zone's text and candidates.
     */
    const val EXTRAS_SCORED = "extras_scored"

    fun slot(json: String) = "slot:$json"
}
