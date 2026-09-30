package com.postsaimanager.core.domain.extraction.address

import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.model.AddressPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one country prints in an address and how a postcode looks, as data (`address/formats.json`). The formats never decide what a
 * line *means* (the model labels the words); they only **verify**: a postcode has the country's shape, the parts the country requires
 * are present, a country line names the country.
 *
 * @property postcodePattern the postcode as a regular expression, or null for a country without postcodes.
 * @property postcodeBeforeCity the postcode stands before the place on their shared line (`12345 Berlin`) rather than after it.
 * @property postcodeOwnLine the postcode usually has a line of its own (under the place).
 * @property lineShape when present, the postcode line must match this expression as a whole (a specific shape such as `Town, ST 12345`).
 * @property houseNumberFirst the house number precedes the street name (`10 Main Street`).
 * @property requires the parts an address of this country must have to count as complete.
 * @property countryNames local and English names of the country; used only to verify a country line.
 * @property lineOrder the parts in the order the country prints them, first line first.
 */
class AddressFormat(
    val iso2: String,
    val postcodePattern: String?,
    val postcodeBeforeCity: Boolean,
    val postcodeOwnLine: Boolean,
    val lineShape: String?,
    val houseNumberFirst: Boolean,
    val requires: Set<AddressPart>,
    val countryNames: List<String>,
    val lineOrder: List<AddressPart>,
) {
    /** The postcode where it stands alone in a line's text (no letter or digit touches it). */
    val postcodeRegex: Regex? = postcodePattern?.let { Regex("(?<![\\p{L}\\d])(?:$it)(?![\\p{L}\\d])", RegexOption.IGNORE_CASE) }

    private val wholePostcode: Regex? = postcodePattern?.let { Regex("(?:$it)", RegexOption.IGNORE_CASE) }
    private val wholeLine: Regex? = lineShape?.let { Regex(it) }

    /** The postcode in [line], or null when the country has none or the line holds none. */
    fun findPostcode(line: String): MatchResult? = postcodeRegex?.find(line)

    /** [value] is, as a whole, a postcode of this country. */
    fun isPostcode(value: String): Boolean = wholePostcode?.matches(value.trim()) == true

    /** Whether [line], whose postcode match is [match], has the position and the shape this format prints. */
    internal fun fits(line: String, match: MatchResult): Boolean {
        if (wholeLine != null && !wholeLine.matches(line.trim())) return false
        // "D-12345 Berlin": a country code joined to the postcode is not a place.
        val head = line.substring(0, match.range.first).trim()
        val before = head.any { it.isLetter() } && !COUNTRY_CODE_PREFIX.matches(head)
        val after = line.substring(match.range.last + 1).any { it.isLetter() }
        // A place has no digit: "Tel. 01234 567890" is a phone number, not a postcode and a place.
        if (line.removeRange(match.range).any { it.isDigit() }) return false
        return when {
            before && after -> false
            before -> !postcodeBeforeCity
            after -> postcodeBeforeCity
            else -> true
        }
    }

    /** Whether the line is the postcode alone (no place beside it). */
    internal fun isAlone(line: String, match: MatchResult): Boolean =
        line.removeRange(match.range).none { it.isLetter() }

    /** [name] is, folded, one of [countryNames]. */
    fun namesCountry(name: String): Boolean = compact(name) in names

    private val names: Set<String> = countryNames.map(::compact).toSet()

    internal companion object {
        private val COUNTRY_CODE_PREFIX = Regex("[A-Za-z]{1,3}\\s*[-–]")

        /** Lowercased, folded (accents, Arabic spellings, digits) with everything but letters and digits removed. */
        fun compact(s: String): String = QuoteVerifier.fold(s).filter { it.isLetterOrDigit() }
    }
}

/**
 * The address formats of the countries in scope, looked up by code, by the shape of a postcode line and by the name of a country.
 * A plain value built from JSON, so a test (or a later release) can build one from another file.
 */
class AddressFormatRegistry(val formats: List<AddressFormat>) {

    private val byIso = formats.associateBy { it.iso2.uppercase() }

    fun of(iso2: String?): AddressFormat? = iso2?.uppercase()?.let { byIso[it] }

    /**
     * The point at which [text], a street and its number followed by a postcode and a place on one line
     * (`Versicherungsallee 15 12345 Beispielstadt`), falls into those two lines; null when it is not such a line.
     */
    fun combinedSplit(text: String): Int? {
        for (f in formats) {
            val m = f.findPostcode(text) ?: continue
            val head = text.substring(0, m.range.first)
            val tail = text.substring(m.range.last + 1)
            val street = head.trimEnd(*SEPARATORS)
            val fused = street.any { it.isLetter() } && STREET_END.containsMatchIn(street)
            if (fused && tail.any { it.isLetter() } && tail.none { it.isDigit() }) return m.range.first
        }
        return null
    }

    /** The format whose country name [line] is (as a whole), or null. */
    fun byCountryName(line: String): AddressFormat? = formats.firstOrNull { it.namesCountry(line) }

    /**
     * The formats that could print [line] as their "postcode place" line: a postcode of the format sits in it, at the position and in
     * the shape the format prints. More specific formats (with a [AddressFormat.lineShape]) win over the general ones; a line with a
     * place beside the postcode prefers formats that print them together, a line with the postcode alone prefers formats that give it
     * its own line. Several formats may remain: the caller takes a country only when exactly one does.
     */
    fun byPostcodeShape(line: String): List<AddressFormat> {
        val hits = formats.mapNotNull { f -> f.findPostcode(line)?.takeIf { f.fits(line, it) }?.let { f to it } }
        if (hits.isEmpty()) return emptyList()
        val specific = hits.filter { it.first.lineShape != null }
        val pool = specific.ifEmpty { hits }
        val preferred = pool.filter { (f, m) -> f.postcodeOwnLine == f.isAlone(line, m) }
        return (preferred.ifEmpty { pool }).map { it.first }
    }
}

private val SEPARATORS = charArrayOf(',', ';', ':', '-', '–', '·', '•', '|', ' ')

/** A house number (with an optional letter) at the end of a text. */
private val STREET_END = Regex("\\d\\s?[A-Za-z]?$")

@Serializable
private class FormatsFile(val formats: List<FormatEntry>)

@Serializable
private class FormatEntry(
    val iso2: String,
    val postcode: String? = null,
    val postcodeBeforeCity: Boolean = false,
    val postcodeOwnLine: Boolean = false,
    val lineShape: String? = null,
    val houseNumberFirst: Boolean = false,
    val requires: List<String> = emptyList(),
    val lineOrder: List<String> = emptyList(),
    val countryNames: List<String> = emptyList(),
)

/**
 * The address formats shipped with the app ([default]) and the loader that reads them from JSON. Loaded through the class loader
 * (a resource of this module), so the domain stays plain Kotlin. Adding a country is adding an entry to `address/formats.json`.
 */
object AddressFormats {

    const val RESOURCE = "/address/formats.json"

    /**
     * The attribution the data's licence (CC-BY-4.0) asks for; shown in Settings (string `settings_address_data_attribution` in
     * `:feature:settings`, which carries the same text).
     */
    const val ATTRIBUTION =
        "Address formats are derived from the Google libaddressinput address data (https://github.com/google/libaddressinput), " +
            "licensed under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Only the postcode patterns, the order of " +
            "the parts, the required parts and country names of a few countries are kept, adapted."

    private val json = Json { ignoreUnknownKeys = true }

    /** The shipped registry. */
    val default: AddressFormatRegistry by lazy {
        val text = AddressFormats::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
            ?: error("the address formats resource $RESOURCE is missing")
        parse(text)
    }

    fun of(iso2: String?): AddressFormat? = default.of(iso2)

    fun byPostcodeShape(line: String): List<AddressFormat> = default.byPostcodeShape(line)

    /** Builds a registry from the JSON text of a formats file. An entry naming an unknown part is an error, never skipped. */
    fun parse(text: String): AddressFormatRegistry {
        val file = json.decodeFromString(FormatsFile.serializer(), text)
        fun parts(keys: List<String>) = keys.map { AddressPart.ofKey(it) ?: error("unknown address part '$it' in the address formats") }
        return AddressFormatRegistry(
            file.formats.map { e ->
                AddressFormat(
                    iso2 = e.iso2.uppercase(),
                    postcodePattern = e.postcode,
                    postcodeBeforeCity = e.postcodeBeforeCity,
                    postcodeOwnLine = e.postcodeOwnLine,
                    lineShape = e.lineShape,
                    houseNumberFirst = e.houseNumberFirst,
                    requires = parts(e.requires).toSet(),
                    countryNames = e.countryNames,
                    lineOrder = parts(e.lineOrder),
                )
            },
        )
    }
}
