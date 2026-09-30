package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * The parts a postal address is made of, as the address formats and the stored rows name them.
 *
 * @property key the suffix of the stored row (`addressee.` or `sender.` plus this); the one owner of those names.
 */
@Serializable
enum class AddressPart(val key: String) {
    RECIPIENT_NAME("name"),
    SALUTATION("salutation"),
    ORGANISATION("organisation"),
    DEPARTMENT("department"),
    CARE_OF("care_of"),
    ATTENTION("attention"),
    STREET("street"),
    HOUSE_NUMBER("house_number"),
    ADDRESS_EXTRA("extra"),
    POSTCODE("postcode"),
    CITY("city"),
    REGION("region"),
    COUNTRY("country"),
    PO_BOX("po_box"),
    PACKSTATION("packstation"),
    ;

    companion object {
        /** The part named [key] in a format's data, or null. */
        fun ofKey(key: String): AddressPart? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One part of an address as read: the printed [value], the line of [PostalAddress.lines] it is on, where that line sits on
 * the page and how sure the extraction is (the shown confidence of `ConfidenceCombiner`: the model's word capped by the checks).
 * For a country the value is the ISO 3166-1 alpha-2 code.
 */
@Serializable
data class AddressPartValue(
    val value: String,
    val lineIdx: Int,
    val bbox: TextBounds? = null,
    val confidence: Float = 0f,
)

/**
 * A postal address read from a letter: the raw [lines] (always kept, even when nothing could be labelled) and the parts found in
 * them. A part the letter does not print is null (or empty for the names). Nothing here is a guess: every value is text of a line.
 *
 * @property page the page the lines are on.
 * @property formatId the ISO code of the address format the parts were checked against, null when none applied.
 * @property verified the format was known, every part it requires is present, the postcode has the format's shape and the
 *   lines form one block.
 * @property notes what the checks found (missing parts, an unknown country line, a household), as structure words, for
 *   diagnostics; never text of the letter.
 */
@Serializable
data class PostalAddress(
    val lines: List<String>,
    val page: Int = 1,
    val recipientNames: List<AddressPartValue> = emptyList(),
    val salutation: AddressPartValue? = null,
    val organisation: AddressPartValue? = null,
    val department: AddressPartValue? = null,
    val careOf: AddressPartValue? = null,
    val attention: AddressPartValue? = null,
    val street: AddressPartValue? = null,
    val houseNumber: AddressPartValue? = null,
    val addressExtra: AddressPartValue? = null,
    val postcode: AddressPartValue? = null,
    val city: AddressPartValue? = null,
    val region: AddressPartValue? = null,
    val countryIso2: AddressPartValue? = null,
    val poBox: AddressPartValue? = null,
    val packstation: AddressPartValue? = null,
    val formatId: String? = null,
    val verified: Boolean = false,
    val notes: List<String> = emptyList(),
) {
    /** The single value of [part]; for the names, the first one. */
    fun part(part: AddressPart): AddressPartValue? = when (part) {
        AddressPart.RECIPIENT_NAME -> recipientNames.firstOrNull()
        AddressPart.SALUTATION -> salutation
        AddressPart.ORGANISATION -> organisation
        AddressPart.DEPARTMENT -> department
        AddressPart.CARE_OF -> careOf
        AddressPart.ATTENTION -> attention
        AddressPart.STREET -> street
        AddressPart.HOUSE_NUMBER -> houseNumber
        AddressPart.ADDRESS_EXTRA -> addressExtra
        AddressPart.POSTCODE -> postcode
        AddressPart.CITY -> city
        AddressPart.REGION -> region
        AddressPart.COUNTRY -> countryIso2
        AddressPart.PO_BOX -> poBox
        AddressPart.PACKSTATION -> packstation
    }

    /** Every part with its value, the names one by one, in the order of [AddressPart]. */
    val parts: List<Pair<AddressPart, AddressPartValue>>
        get() = AddressPart.entries.flatMap { p ->
            if (p == AddressPart.RECIPIENT_NAME) recipientNames.map { p to it } else listOfNotNull(part(p)?.let { p to it })
        }

    /** The lowest confidence among the parts; 0 when nothing was labelled. */
    val confidence: Float get() = parts.minOfOrNull { it.second.confidence } ?: 0f
}
