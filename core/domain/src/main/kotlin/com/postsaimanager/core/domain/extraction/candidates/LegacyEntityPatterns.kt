package com.postsaimanager.core.domain.extraction.candidates

/**
 * The regexes and helpers that used to live inside `EntityExtractor` (core:data), moved here
 * as pure functions with unchanged behaviour. `EntityExtractor` delegates to them; the
 * extraction v2 pipeline (workstream E) replaces their callers with [CandidateExtractor] and
 * this object can then be retired.
 */
object LegacyEntityPatterns {

    /** Labelled reference numbers, first hit per label, as (legacy field name, value). */
    fun referenceNumbers(text: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val patterns = listOf(
            // These four capture to end-of-line and are then narrowed by [cleanReferenceValue].
            Regex("(?i)(?:aktenzeichen|az\\.?)\\s*[:.]?\\s*([^\\r\\n]{3,60})") to "File Reference (Aktenzeichen)",
            Regex("(?i)(?:geschäftszeichen|gz\\.?)\\s*[:.]?\\s*([^\\r\\n]{3,60})") to "Business Reference",
            Regex("(?i)(?:unser zeichen|uns\\.?\\s*z(?:eichen)?)\\s*[:.]?\\s*([^\\r\\n]{3,60})") to "Our Reference",
            Regex("(?i)(?:ihr zeichen)\\s*[:.]?\\s*([^\\r\\n]{3,60})") to "Your Reference",
            Regex("(?i)(?:kunden[\\-\\s]?nr\\.?|kundennummer)\\s*[:.]?\\s*([A-Za-z0-9\\-]{3,20})") to "Customer Number",
            Regex("(?i)(?:vertrags[\\-\\s]?nr\\.?|vertragsnummer)\\s*[:.]?\\s*([A-Za-z0-9\\-]{3,20})") to "Contract Number",
            Regex("(?i)(?:rechnungs[\\-\\s]?nr\\.?|rechnungsnummer)\\s*[:.]?\\s*([A-Za-z0-9\\-]{3,20})") to "Invoice Number",
            Regex("(?i)(?:steuer[\\-\\s]?nr\\.?|steuernummer)\\s*[:.]?\\s*([0-9/\\-]{5,20})") to "Tax Number",
            Regex("(?i)(?:steuer[\\-\\s]?id|steueridentifikationsnummer)\\s*[:.]?\\s*(\\d{11})") to "Tax ID",
            Regex("(?i)(?:versicherungs[\\-\\s]?nr\\.?)\\s*[:.]?\\s*([A-Za-z0-9\\-]{3,20})") to "Insurance Number",
        )
        for ((regex, label) in patterns) {
            regex.find(text)?.let { match ->
                val value = cleanReferenceValue(match.groupValues[1])
                if (value.isNotBlank()) results.add(label to value)
            }
        }
        return results
    }

    /**
     * Trims a captured reference down to the identifier itself.
     *
     * A reference number is a run of alphanumeric tokens, possibly separated by single
     * spaces ("BG 1234/5678"). Prose begins at the first purely alphabetic word of three
     * or more characters, which is where the value ends.
     */
    fun cleanReferenceValue(raw: String): String {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = tokens.takeWhile { token ->
            token.any { it.isDigit() } || token.length <= 2 || token.any { !it.isLetter() }
        }
        return kept.joinToString(" ").trim().trimEnd('.', ',', ';', ':', '-')
    }

    /** IBANs from any country that pass the mod-97 checksum, compacted, in order, distinct. */
    fun ibans(text: String): List<String> =
        Regex("\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,3})?\\b")
            .findAll(text)
            .map { it.value.replace(Regex("\\s"), "").uppercase() }
            .filter(IbanValidator::hasValidChecksum)
            .toList()
            .distinct()

    /** German-format euro amounts ("1.234,56 €", "EUR 1.234,56"), rendered "1.234,56 €", at most five. */
    fun euroAmounts(text: String): List<String> {
        val amounts = Regex("(?:EUR|€)\\s*([\\d.]+,\\d{2})|([\\d.]+,\\d{2})\\s*(?:EUR|€)", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { match ->
                val raw = (match.groupValues[1].ifBlank { match.groupValues[2] })
                "$raw €"
            }
            .toList().distinct()
        return amounts.take(5)
    }

    /** Legacy deadline hits: labelled dates as printed, or the number of a relative period ("14"). At most three. */
    fun deadlines(text: String): List<String> {
        val results = mutableListOf<String>()
        val patterns = listOf(
            Regex("(?i)(?:frist|bis zum|spätestens|deadline|bis spätestens)\\s*[:.]?\\s*(\\d{1,2}\\.\\d{1,2}\\.\\d{4})"),
            Regex("(?i)(?:frist|bis zum|spätestens)\\s*[:.]?\\s*(\\d{1,2}\\.\\s*(?:Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember)\\s*\\d{4})"),
            Regex("(?i)(?:innerhalb von|within|binnen)\\s+(\\d+)\\s+(?:Tagen?|Wochen?|Monaten?|days?|weeks?|months?)"),
        )
        for (regex in patterns) {
            regex.findAll(text).forEach { match ->
                results.add(match.groupValues[1].trim())
            }
        }
        return results.distinct().take(3)
    }

    /**
     * E-mail addresses. Every dot must be followed by label characters, so a sentence-ending
     * period is not swallowed ("info@example.com.") while multi-part domains (co.uk) still work.
     */
    fun emails(text: String): List<String> =
        Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+")
            .findAll(text).map { it.value }.toList().distinct()

    /** Labelled telephone and fax numbers as printed. */
    fun phoneNumbers(text: String): List<String> {
        val patterns = listOf(
            Regex("(?i)(?:tel\\.?|telefon|fon|phone|mobil)\\s*[:.]?\\s*([+\\d\\s\\-/()]{8,})"),
            Regex("(?i)(?:fax)\\s*[:.]?\\s*([+\\d\\s\\-/()]{8,})"),
        )
        return patterns.flatMap { regex ->
            regex.findAll(text).map { it.groupValues[1].trim() }
        }.distinct()
    }
}
