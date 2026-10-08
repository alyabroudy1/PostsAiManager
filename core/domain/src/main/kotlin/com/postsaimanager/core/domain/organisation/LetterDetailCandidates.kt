package com.postsaimanager.core.domain.organisation

import com.postsaimanager.core.domain.extraction.address.AddressFormats
import com.postsaimanager.core.domain.extraction.address.AddressRows
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.IbanValidator
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.model.AddressPart
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.PostalValue
import com.postsaimanager.core.model.ReviewState

/** What kind of detail a letter value may be for an organisation. */
enum class DetailKind { PHONE, EMAIL, WEBSITE, IBAN }

/** A value of a letter that might be a detail of its sender: [value] as printed, and what shape it has. */
data class DetailCandidate(val kind: DetailKind, val value: String)

/**
 * What a letter shows that might be a detail of its sender organisation, found from what the reading stored and the letter's own text:
 * the sender's verified postal address, and the phone numbers, e-mail addresses, websites and bank accounts. This only collects and
 * verifies **shape and presence** (the value is in the letter, an e-mail looks like an e-mail, the address passed its checks); whose
 * a value is (the organisation's general line or a person's direct line) is the model's decision ([DecideDetailOwnerUseCase]).
 */
object LetterDetailCandidates {

    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+")
    private val WEBSITE = Regex("(?i)(?<![\\w@.])(?:https?://|www\\.)[^\\s,;<>()\\[\\]\"']+")
    private val PHONE_WHOLE = Regex("^\\+?\\d[\\d ()/.\\-]{4,}\\d$")
    private val IBAN_WHOLE = Regex("^[A-Za-z]{2}\\d{2}[A-Za-z0-9 ]{10,32}$")
    private val IBAN_SHAPE = Regex("(?<![A-Za-z0-9])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{1,4}){3,8}")

    /** An international number with a plus, or one that starts with 0 and has at least two more groups: by shape, whatever the words around it. */
    private val PHONE_SHAPE = Regex(
        "(?<![\\p{L}\\d+./-])(?:\\+\\d{1,3}(?:[ ./()-]?\\d){6,12}|0\\d{1,4}(?:[ /()-]\\d{2,}){2,})(?![\\p{L}\\d])",
    )
    private val DATE_SHAPE = Regex("^\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}$")
    private const val IBAN_GROUP = 4
    private const val MIN_PHONE_DIGITS = 6
    private const val MAX_PHONE_DIGITS = 15

    private val ibanSlots: Set<String> by lazy {
        ExtractionSchema.DEFAULT.allSlots.filter { it.kind == SlotKind.IBAN }.map { it.json }.toSet()
    }

    /** The sender's address as the profile stores it, from the verified `sender.*` rows; null when the reading found none. */
    fun senderAddress(fields: List<ExtractedData>): PostalValue? {
        val rows = fields.filter { live(it) && it.slotKey?.startsWith("sender.") == true && it.origin == AddressRows.ORIGIN_VERIFIED }
            .associateBy { it.slotKey!!.removePrefix("sender.") }
        fun part(p: AddressPart) = rows[p.key]?.fieldValue?.trim()?.takeIf { it.isNotEmpty() }
        val street = part(AddressPart.STREET)
        val number = part(AddressPart.HOUSE_NUMBER)
        val numberFirst = AddressFormats.of(part(AddressPart.COUNTRY))?.houseNumberFirst == true
        val streetLine = listOfNotNull(street, number).let { if (numberFirst) it.reversed() else it }.joinToString(" ").takeIf { it.isNotEmpty() }
        val address = PostalValue(street = streetLine, postalCode = part(AddressPart.POSTCODE), city = part(AddressPart.CITY))
        return address.takeUnless { it.isEmpty }
    }

    /** The phone numbers, e-mail addresses, websites and accounts of the letter, each once, that are in [letterText]. */
    fun details(fields: List<ExtractedData>, letterText: String): List<DetailCandidate> {
        val found = LinkedHashMap<String, DetailCandidate>()
        // A value the user ignored on the letter is not offered again from the letter's text either.
        val ignored = fields.filterNot(::live).filter { it.fieldValue.isNotBlank() }.map { it.fieldValue.trim() }
        fun add(kind: DetailKind, raw: String) {
            val value = raw.trim().trimEnd('.', ',', ';', ':')
            if (value.isEmpty() || !presentIn(kind, value, letterText)) return
            if (ignored.any { normalised(kind, it) == normalised(kind, value) }) return
            found.putIfAbsent("$kind:${normalised(kind, value)}", DetailCandidate(kind, value))
        }
        for (field in fields.filter(::live)) {
            if (AddressRows.isAddressKey(field.slotKey) || field.slotKey in PARTY_SLOTS) continue
            val value = field.fieldValue.trim()
            val foundKind = ExtractionV2Adapter.parseFoundKey(field.slotKey)?.first
            when {
                foundKind == CandidateKind.PHONE || field.fieldType == ExtractedFieldType.PHONE -> add(DetailKind.PHONE, value)
                foundKind == CandidateKind.EMAIL || field.fieldType == ExtractedFieldType.EMAIL -> add(DetailKind.EMAIL, value)
                foundKind == CandidateKind.IBAN || field.fieldType == ExtractedFieldType.IBAN || field.slotKey in ibanSlots ->
                    if (IBAN_WHOLE.matches(value)) add(DetailKind.IBAN, value)
                EMAIL.matches(value) -> add(DetailKind.EMAIL, value)
                WEBSITE.matches(value) -> add(DetailKind.WEBSITE, value)
                isPhoneShaped(value) && foundKind == null && !field.isDateLike() -> add(DetailKind.PHONE, value)
            }
        }
        // The letter's own text: the reading stores only the values it chose, and a footer's phone, e-mail or website is usually not one of
        // them. The shapes are structural (no word is interpreted); whose each value is, is the model's question.
        EMAIL.findAll(letterText).forEach { add(DetailKind.EMAIL, it.value) }
        WEBSITE.findAll(letterText).forEach { add(DetailKind.WEBSITE, it.value) }
        val accounts = IBAN_SHAPE.findAll(letterText).mapNotNull { accountIn(it.value) }.toList()
        accounts.forEach { add(DetailKind.IBAN, it) }
        val withoutAccounts = accounts.fold(letterText) { text, iban -> text.replace(Regex(ibanPattern(iban), RegexOption.IGNORE_CASE), " ") }
        PHONE_SHAPE.findAll(withoutAccounts).map { it.value.trim() }.filterNot(::looksLikeDate).forEach { add(DetailKind.PHONE, it) }
        return found.values.toList()
    }

    /** The valid account at the start of [text] (the country's length, checksum right), grouped in fours; null when it is none. */
    private fun accountIn(text: String): String? {
        val compact = IbanValidator.compact(text)
        val length = IbanValidator.lengths[compact.take(2)] ?: return null
        if (compact.length < length) return null
        val iban = compact.take(length)
        return iban.takeIf { IbanValidator.hasValidChecksum(it) }?.chunked(IBAN_GROUP)?.joinToString(" ")
    }

    /** A pattern that finds [iban] in a text whatever its spacing. */
    private fun ibanPattern(iban: String): String = iban.filterNot { it == ' ' }.toList().joinToString(" ?") { Regex.escape(it.toString()) }

    private fun looksLikeDate(value: String) = DATE_SHAPE.matches(value)

    private val PARTY_SLOTS = setOf("sender", "addressee", "contact", "subject_person", "subject")

    private fun live(field: ExtractedData) = !field.deletedByUser && field.reviewState != ReviewState.IGNORED && field.fieldValue.isNotBlank()

    private fun ExtractedData.isDateLike() = fieldType == ExtractedFieldType.DATE || fieldType == ExtractedFieldType.DEADLINE

    private fun isPhoneShaped(value: String): Boolean {
        if (!PHONE_WHOLE.matches(value)) return false
        return value.count { it.isDigit() } in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS && (value.startsWith("+") || value.startsWith("0"))
    }

    private fun normalised(kind: DetailKind, value: String): String = when (kind) {
        DetailKind.PHONE -> value.filter { it.isDigit() }
        DetailKind.IBAN -> value.filterNot { it.isWhitespace() }.uppercase()
        DetailKind.EMAIL, DetailKind.WEBSITE -> value.lowercase()
    }

    /** The value is in the letter's text (compared as the kind normalises it); with no text to check against, it is not. */
    private fun presentIn(kind: DetailKind, value: String, letterText: String): Boolean {
        if (letterText.isBlank()) return false
        return when (kind) {
            DetailKind.PHONE -> normalised(kind, value).let { digits -> letterText.lineSequence().any { digitsOf(it).contains(digits) } }
            DetailKind.IBAN -> letterText.filterNot { it.isWhitespace() }.uppercase().contains(normalised(kind, value))
            DetailKind.EMAIL, DetailKind.WEBSITE -> letterText.contains(value, ignoreCase = true)
        }
    }

    private fun digitsOf(text: String): String = text.filter { it.isDigit() }
}
