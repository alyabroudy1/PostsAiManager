package com.postsaimanager.core.domain.extraction.address

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.layout.AddressShapes
import com.postsaimanager.core.domain.extraction.layout.AddressShapes.COUNTRY_CODE_PREFIX
import com.postsaimanager.core.domain.extraction.layout.AddressShapes.LEADING_NUMBER
import com.postsaimanager.core.domain.extraction.layout.AddressShapes.SEPARATORS
import com.postsaimanager.core.domain.extraction.layout.AddressShapes.TRAILING_NUMBER
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.Parties
import com.postsaimanager.core.domain.extraction.v2.Party
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.PartyRelation
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import com.postsaimanager.core.domain.extraction.zones.ZonePrompt
import com.postsaimanager.core.domain.extraction.zones.ZoneScoringInterpreter
import com.postsaimanager.core.domain.extraction.zones.ZonedLetter
import com.postsaimanager.core.model.AddressPart
import kotlin.math.abs

/** Where the country of an address came from. */
enum class CountrySource { LINE, POSTCODE_SHAPE, NONE }

/**
 * One part found in a line, before it is checked: the [ai] confidence is the model's (or, for a part found by shape and confirmed by a
 * format, the shape's) own word as [ConfidenceCombiner] numbers it; [AddressVerifier] caps it by what it checks.
 */
class LabeledPart(val part: AddressPart, val value: String, val lineIdx: Int, val ai: Float)

/**
 * A block of lines with its parts labelled, not yet verified.
 *
 * @property cells how many scores the labelling cost (one line under one statement is one cell).
 * @property hasPostcodeLine a line holding a postcode was found (by the country's format, or by shape when no country is known).
 */
class LabeledAddress(
    val lines: List<AddressLine>,
    val parts: List<LabeledPart>,
    val format: AddressFormat?,
    val countrySource: CountrySource,
    val countryLineIdx: Int?,
    val hasPostcodeLine: Boolean,
    val notes: List<String>,
    val cells: Int = 0,
)

/**
 * Reads the lines of an address block into parts (stage 9 of the extraction): **structural lines by shape, the rest by score**.
 *
 * - **Shape pass** (no scores): the country line (a line that *is* a country name of the formats), the "postcode place" line (a
 *   postcode of a format, at the position the format prints it), the place lines a format puts above or below the postcode, the
 *   street line (a word and a number) with its house number, and lines whose meaning the letter's parties already settled (a line
 *   that is, as a whole, the name the model gave a party).
 * - **Scoring pass** on what is left: the word-only lines are scored under [LineAsk.LABELS] (person, organisation, department,
 *   routing) and a street-shaped line under [LineAsk.DELIVERY_POINTS] (a post office box or a locker has no house number). One grid
 *   each, through [PromptSession.scoreGrid]; the labels are never generated, so there is no label-token bias. The winner needs the
 *   profile's abstain threshold; the confidence comes from the profile's [ScoringProfile.cuts] like every other scored answer.
 *
 * The budget ([Scoring.budget]) caps the cells: the delivery-point cells first (at most two lines), the rest for word-only lines,
 * four cells each. A line that did not fit, or that no label won, stays a raw line ([AddressPart.ADDRESS_EXTRA]).
 *
 * The country is the country line's, else the one format whose postcode shape matches the postcode line, else none (the verifier caps
 * the confidence). No country or language is named here: the formats are data ([AddressFormatRegistry]).
 */
class AddressLineLabeler(
    private val formats: AddressFormatRegistry = AddressFormats.default,
    private val profile: ScoringProfile = ScoringProfile(),
) {

    /** What scoring needs: the open letter session, the most cells to spend, and the closing text the session's questions end with. */
    class Scoring(val session: PromptSession, val budget: Int, val tail: String = "")

    /** The shape pass alone (0 scores). Word-only lines stay raw unless a party settled them. */
    fun shape(lines: List<AddressLine>, parties: Parties = Parties()): LabeledAddress = finish(shapePass(lines, parties))

    /** The shape pass, then the scoring pass on what the shape pass left; without [scoring] it is [shape]. */
    suspend fun label(lines: List<AddressLine>, parties: Parties = Parties(), scoring: Scoring? = null): LabeledAddress {
        val shaped = shapePass(lines, parties)
        if (scoring == null) return finish(shaped)
        return scored(shaped, scoring)
    }

    // ── shape pass ──

    /** The shape pass's findings: the parts it settled, and the line indexes it left for the scoring pass. */
    private class Shaped(
        val lines: List<AddressLine>,
        val parts: MutableList<LabeledPart>,
        val notes: MutableList<String>,
        val format: AddressFormat?,
        val countrySource: CountrySource,
        val countryLineIdx: Int?,
        val postcodeLineIdx: Int?,
        /** Street-shaped lines, the nearest to the postcode first (at most [MAX_DELIVERY_LINES]). */
        val streetCandidates: List<Int>,
        /** Word-only lines no party settled, in page order. */
        val wordLines: List<Int>,
        /** Lines that stay raw unless scoring settles them. */
        val extraLines: List<Int>,
    )

    /** Without scores the nearest street-shaped line is the street, and what was left stays raw. */
    private fun finish(s: Shaped): LabeledAddress {
        val all = s.parts.toMutableList()
        s.streetCandidates.firstOrNull()?.let { all += streetParts(s, it, STRUCTURAL_AI) }
        all += extraParts(s, s.extraLines + s.wordLines + s.streetCandidates.drop(1))
        return result(s, all, 0, emptyList())
    }

    private fun extraParts(s: Shaped, indexes: List<Int>): List<LabeledPart> {
        val sorted = indexes.sorted()
        val first = sorted.firstOrNull() ?: return emptyList()
        return listOf(LabeledPart(AddressPart.ADDRESS_EXTRA, sorted.joinToString(", ") { s.lines[it].text }, first, LOW_AI))
    }

    private fun result(s: Shaped, all: List<LabeledPart>, cells: Int, more: List<String>) = LabeledAddress(
        s.lines, all, s.format, s.countrySource, s.countryLineIdx, s.postcodeLineIdx != null, s.notes + more, cells,
    )

    private fun streetParts(s: Shaped, idx: Int, ai: Float): List<LabeledPart> {
        val (street, number) = splitStreet(s.lines[idx].text, s.format?.houseNumberFirst ?: false)
        return listOfNotNull(
            LabeledPart(AddressPart.STREET, street, idx, ai),
            number?.let { LabeledPart(AddressPart.HOUSE_NUMBER, it, idx, ai) },
        )
    }

    private fun shapePass(input: List<AddressLine>, parties: Parties): Shaped {
        val lines = AddressLines.splitCombined(input, formats)
        val n = lines.size
        val used = BooleanArray(n)
        val parts = mutableListOf<LabeledPart>()
        val notes = mutableListOf<String>()
        var format: AddressFormat? = null
        var source = CountrySource.NONE
        var countryIdx: Int? = null
        if (n > 0) {
            formats.byCountryName(lines[n - 1].text)?.let {
                format = it
                source = CountrySource.LINE
                countryIdx = n - 1
                used[n - 1] = true
                parts += LabeledPart(AddressPart.COUNTRY, it.iso2, n - 1, HIGH_AI)
            }
        }

        // The postcode line: the last one that holds a postcode of a format (of the known country's format, else of any).
        var postcodeIdx: Int? = null
        var candidates: List<AddressFormat> = emptyList()
        var postcode: MatchResult? = null
        for (i in (if (countryIdx != null) n - 2 else n - 1) downTo 0) {
            val text = lines[i].text
            val known = format
            if (known != null) {
                known.findPostcode(text)?.takeIf { known.fits(text, it) }?.let { postcode = it; candidates = listOf(known) }
            } else {
                formats.byPostcodeShape(text).takeIf { it.isNotEmpty() }?.let { hits ->
                    postcode = hits.first().findPostcode(text)
                    candidates = hits
                } ?: run {
                    if (AddressShapes.isPostcodeLine(text)) {
                        postcode = AddressShapes.DIGIT_RUN.find(text) ?: AddressShapes.ALNUM_POSTCODE.find(text)
                    }
                }
            }
            if (postcode != null) {
                postcodeIdx = i
                break
            }
        }

        val pc = postcode
        if (pc != null && postcodeIdx != null) {
            val p = postcodeIdx
            used[p] = true
            // A country line no format names: a foreign address. Its postcode shape says nothing about the country.
            val foreign = countryIdx == null && p + 1 == n - 1 && AddressShapes.isCountryText(lines[n - 1].text)
            if (foreign) {
                used[n - 1] = true
                notes += NOTE_COUNTRY_LINE_UNKNOWN
            }
            // The country: the country line's, else the unique format whose shape matches.
            if (format == null && !foreign && candidates.size == 1) {
                format = candidates.first()
                source = CountrySource.POSTCODE_SHAPE
                countryIdx = p
                parts += LabeledPart(AddressPart.COUNTRY, candidates.first().iso2, p, HIGH_AI)
            }
            val matched = format != null || candidates.isNotEmpty()
            val ai = if (matched) HIGH_AI else ConfidenceCombiner.MEDIUM
            parts += LabeledPart(AddressPart.POSTCODE, pc.value.trim(), p, ai)
            val remainder = remainderOf(lines[p].text, pc)
            if (remainder.isNotBlank()) {
                val (city, region) = splitCity(remainder, format)
                parts += LabeledPart(AddressPart.CITY, city, p, ai)
                region?.let { parts += LabeledPart(AddressPart.REGION, it, p, ai) }
            } else {
                // The postcode has its own line: the places the format prints above it are the word-only lines right over it.
                trailingParts(format).let { above -> assignUp(above, p - 1, lines, used, parts, HIGH_AI) }
            }
        } else if (format != null) {
            // A country without postcodes: the parts the format prints after the street are the last word-only lines.
            val last = (countryIdx ?: n) - 1
            if ((0..last).any { isStreetShaped(lines[it].text) }) {
                assignUp(trailingParts(format), last, lines, used, parts, ConfidenceCombiner.MEDIUM)
            }
        }

        // What is left above the postcode (or everywhere when there is none): street-shaped, word-only, or other.
        val street = mutableListOf<Int>()
        val words = mutableListOf<Int>()
        val extras = mutableListOf<Int>()
        for (i in 0 until n) {
            if (used[i]) continue
            val t = lines[i].text
            when {
                postcodeIdx != null && i > postcodeIdx -> extras += i
                isStreetShaped(t) -> street += i
                AddressShapes.isWordOnly(t) -> words += i
                else -> extras += i
            }
        }
        // Only the two lines nearest the postcode may be a street or a delivery point; the rest stay raw.
        val nearest = street.sortedDescending().take(MAX_DELIVERY_LINES)
        extras += street.filter { it !in nearest }

        // Lines a party already settled need no score.
        val settled = mutableSetOf<Int>()
        settleByParties(lines, words, parties, parts, notes, settled)

        return Shaped(lines, parts, notes, format, source, countryIdx, postcodeIdx, nearest, words.filter { it !in settled }, extras)
    }

    // ── scoring pass ──

    private suspend fun scored(shaped: Shaped, scoring: Scoring): LabeledAddress {
        val lines = shaped.lines
        val parts = shaped.parts.toMutableList()
        val raw = shaped.extraLines.toMutableList()
        val notes = mutableListOf<String>()
        var cells = 0
        val shared = "\n\n" + BLOCK_TITLE + "\n" + lines.joinToString("\n") { it.text }

        fun head(idx: Int) = ZonePrompt.scoringHead(
            lines[idx].text, ZonedLetter.Context(lines.getOrNull(idx - 1)?.text, null, lines.getOrNull(idx + 1)?.text),
        )

        fun asks(list: List<LineAsk>) = list.map { ZonePrompt.scoringAsk(it.statement) + scoring.tail }

        suspend fun grid(idxs: List<Int>, list: List<LineAsk>): List<List<Double>>? {
            if (idxs.isEmpty()) return emptyList()
            cells += idxs.size * list.size
            val result = scoring.session.scoreGrid(shared, idxs.map(::head), asks(list), ZoneScoringInterpreter.YES, ZoneScoringInterpreter.NO)
            val data = (result as? PamResult.Success)?.data?.takeIf { g -> g.size == idxs.size && g.all { it.size == list.size } }
            if (data == null) notes += NOTE_SCORING_FAILED
            return data
        }

        // The delivery points first: a street-shaped line is a street unless the model says it is a box or a locker.
        val deliveryLines = shaped.streetCandidates
        val deliveryScores = grid(deliveryLines, LineAsk.DELIVERY_POINTS)
        var streetTaken = false
        deliveryLines.forEachIndexed { k, idx ->
            val row = deliveryScores?.get(k)
            val threshold = profile.threshold(LineAsk.DELIVERY_ASK)
            val best = row?.indices?.maxByOrNull { row[it] }
            if (row != null && best != null && row[best] > threshold) {
                val ask = LineAsk.DELIVERY_POINTS[best]
                parts += LabeledPart(ask.part, lines[idx].text, idx, aiOf(abs(row[best] - threshold), abs(row[best])))
            } else if (!streetTaken) {
                streetTaken = true
                val ai = if (row == null) STRUCTURAL_AI else aiOf(abs((row.maxOrNull() ?: 0.0) - threshold), abs(row.maxOrNull() ?: 0.0))
                parts += streetParts(shaped, idx, ai)
            } else {
                raw += idx
            }
        }

        // The word-only lines: as many as the rest of the budget pays for, four cells each.
        val perLine = LineAsk.LABELS.size
        val fit = ((scoring.budget - cells) / perLine).coerceAtLeast(0)
        val scoredWords = shaped.wordLines.take(fit)
        if (shaped.wordLines.size > scoredWords.size) {
            notes += NOTE_BUDGET
            raw += shaped.wordLines.drop(scoredWords.size)
        }
        val wordScores = grid(scoredWords, LineAsk.LABELS)
        scoredWords.forEachIndexed { k, idx ->
            val row = wordScores?.get(k)
            if (row == null) {
                raw += idx
                return@forEachIndexed
            }
            val order = row.indices.sortedByDescending { row[it] }
            val best = order.first()
            val margin = if (order.size > 1) row[best] - row[order[1]] else row[best]
            if (row[best] > profile.threshold(LineAsk.LABEL_ASK)) {
                parts += LabeledPart(LineAsk.LABELS[best].part, lines[idx].text, idx, aiOf(margin, row[best]))
            } else {
                raw += idx
            }
        }

        parts += extraParts(shaped, raw)
        return result(shaped, parts, cells, notes)
    }

    /** The shown-confidence word of a scored answer as its number, from the profile's cut points: the existing bands, no new ones. */
    private fun aiOf(margin: Double, best: Double): Float = ConfidenceCombiner.aiScore(profile.confidence(margin, best))

    // ── helpers ──

    /** A word followed by a number, or a word with a long number: a street (with its house number) or a delivery point. */
    private fun isStreetShaped(text: String): Boolean =
        AddressShapes.STREET_NUMBER.containsMatchIn(text) || AddressShapes.NUMBER_STREET.matches(text.trim()) ||
            AddressShapes.LONG_NUMBER_TAIL.containsMatchIn(text)

    /** The parts a format prints between the street and the postcode (place, region), first line first. */
    private fun trailingParts(format: AddressFormat?): List<AddressPart> {
        val order = format?.lineOrder ?: return emptyList()
        val fromStreet = order.dropWhile { it != AddressPart.STREET }.drop(1)
        return fromStreet.takeWhile { it != AddressPart.POSTCODE }.filter { it == AddressPart.CITY || it == AddressPart.REGION }
    }

    /** Gives [parts] (first line first) to the word-only lines ending at [from], the last part to the line at [from]. */
    private fun assignUp(
        order: List<AddressPart>,
        from: Int,
        lines: List<AddressLine>,
        used: BooleanArray,
        out: MutableList<LabeledPart>,
        ai: Float,
    ) {
        var idx = from
        for (part in order.asReversed()) {
            if (idx < 0 || used[idx] || !AddressShapes.isWordOnly(lines[idx].text)) return
            out += LabeledPart(part, cleaned(lines[idx].text), idx, ai)
            used[idx] = true
            idx--
        }
    }

    /** What is left of the postcode line without the postcode (and a country code joined to it: `D-12345`). */
    private fun remainderOf(line: String, match: MatchResult): String {
        val head = line.substring(0, match.range.first).replace(COUNTRY_CODE_PREFIX, "")
        return cleaned(head + " " + line.substring(match.range.last + 1))
    }

    private fun cleaned(s: String): String = s.trim().trim(*SEPARATORS).trim().replace(Regex("\\s+"), " ")

    /** Place and region of a postcode line's remainder: a region follows a comma when the format prints one. */
    private fun splitCity(remainder: String, format: AddressFormat?): Pair<String, String?> {
        if (format != null && AddressPart.REGION in format.lineOrder && ',' in remainder) {
            val cut = remainder.lastIndexOf(',')
            val city = cleaned(remainder.substring(0, cut))
            val region = cleaned(remainder.substring(cut + 1))
            if (city.isNotEmpty() && region.isNotEmpty()) return city to region
        }
        return remainder to null
    }

    /** Street and house number of a street-shaped line; the number leads when the country prints it first (and only it matches that way). */
    private fun splitStreet(text: String, numberFirst: Boolean): Pair<String, String?> {
        // A long number in groups (`Postfach 10 11 22`) is a delivery point's number, not a house number.
        if (AddressShapes.LONG_NUMBER_TAIL.containsMatchIn(text.trim())) return text.trim() to null
        val trailing = TRAILING_NUMBER.matchEntire(text.trim())
        val leading = LEADING_NUMBER.matchEntire(text.trim())
        return when {
            trailing != null && leading != null ->
                if (numberFirst) leading.groupValues[2] to leading.groupValues[1] else trailing.groupValues[1] to trailing.groupValues[2]
            trailing != null -> trailing.groupValues[1] to trailing.groupValues[2]
            leading != null -> leading.groupValues[2] to leading.groupValues[1]
            else -> text.trim() to null
        }
    }

    /**
     * Lines that are, as a whole, the name the model gave a party are labelled by that party, at the model's own confidence: a person
     * or a household is a recipient name, a company or an authority an organisation, a care-of or routing party a routing line. A line
     * that holds the names of two or more recipients is one line of several names.
     */
    private fun settleByParties(
        lines: List<AddressLine>,
        words: List<Int>,
        parties: Parties,
        parts: MutableList<LabeledPart>,
        notes: MutableList<String>,
        settled: MutableSet<Int>,
    ) {
        if (parties.all.isEmpty()) return
        val recipients = parties.allAddressees
        for (idx in words) {
            val line = AddressFormat.compact(lines[idx].text)
            val inLine = recipients.filter { compactName(it).let { name -> name.length >= MIN_NAME_CHARS && name in line } }
            if (inLine.map(::compactName).distinct().size >= 2) {
                for (p in inLine) parts += LabeledPart(AddressPart.RECIPIENT_NAME, p.name, idx, p.value.aiConfidence)
                notes += NOTE_MULTI_PERSON
                settled += idx
                continue
            }
            val match = parties.all.firstOrNull { compactName(it) == line } ?: continue
            val part = when {
                match.role == PartyRole.CARE_OF || match.role == PartyRole.ROUTING -> AddressPart.CARE_OF
                match.kind == PartyKind.COMPANY || match.kind == PartyKind.AUTHORITY -> AddressPart.ORGANISATION
                else -> AddressPart.RECIPIENT_NAME
            }
            if (match.relation == PartyRelation.HOUSEHOLD) notes += NOTE_HOUSEHOLD
            parts += LabeledPart(part, lines[idx].text, idx, match.value.aiConfidence)
            settled += idx
        }
    }

    private fun compactName(p: Party): String = AddressFormat.compact(p.name)

    companion object {
        const val NOTE_COUNTRY_LINE_UNKNOWN = "country_line_unrecognised"
        const val NOTE_SCORING_FAILED = "scoring_failed"
        const val NOTE_BUDGET = "score_budget_exceeded"
        const val NOTE_MULTI_PERSON = "multi_person_line"
        const val NOTE_HOUSEHOLD = "household"

        /** The most street-shaped lines scored as a delivery point. */
        const val MAX_DELIVERY_LINES = 2

        private const val HIGH_AI = ConfidenceCombiner.HIGH

        /** A part found by shape that no format or score confirmed. */
        private const val STRUCTURAL_AI = ConfidenceCombiner.MEDIUM

        /** A raw line: kept, never trusted. */
        private const val LOW_AI = ConfidenceCombiner.LOW

        private const val MIN_NAME_CHARS = 4
        private const val BLOCK_TITLE = "[address-block]"
    }
}
