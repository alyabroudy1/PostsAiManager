package com.postsaimanager.core.domain.extraction.address

import com.postsaimanager.core.domain.extraction.layout.AddressShapes
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner.Check
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.model.AddressPart
import com.postsaimanager.core.model.AddressPartValue
import com.postsaimanager.core.model.PostalAddress

/**
 * The code half of "AI decides, code verifies" for an address: takes a [LabeledAddress] and checks it against the country's
 * [AddressFormat], then states the confidence of every part as [ConfidenceCombiner] does for every other value (the labeller's own
 * word, capped by each failed check, never raised by a passed one; no new bands).
 *
 * The checks:
 * - **the postcode** has the country's shape (a failure caps it at [ConfidenceCombiner.Caps.INVALID]);
 * - **the required parts** of the country are present (a street may be a post office box or a locker); missing ones cap every part at
 *   [ConfidenceCombiner.Caps.INCONSISTENT];
 * - **one block**: no gap between two lines is larger than one block's line gap (same cap);
 * - **the addressee's name** is among the name lines: when the caller knows whom the address is for and none of them is found in the
 *   name and organisation lines, those parts are capped at [ConfidenceCombiner.Caps.ROLE_MISMATCH];
 * - **the country**: known from the country line or from the one format whose postcode shape matches; otherwise every part is
 *   capped at [ConfidenceCombiner.MEDIUM], because nothing was checked against a format.
 *
 * `verified` is true only when a format applied and none of the checks above failed.
 */
class AddressVerifier {

    /**
     * @param expectedNames whom the address is for (the addressee's names, or the sender's name), as the model gave them; empty when unknown.
     * @param page the page the lines are on.
     */
    fun verify(labeled: LabeledAddress, expectedNames: List<String> = emptyList(), page: Int = labeled.lines.firstOrNull()?.page ?: 1): PostalAddress {
        val notes = labeled.notes.toMutableList()
        val format = labeled.format
        val present = labeled.parts.map { it.part }.toSet()

        val missing = format?.requires.orEmpty().filter { required -> !isPresent(required, present) }
        missing.forEach { notes += "missing:${it.key}" }

        val postcode = labeled.parts.firstOrNull { it.part == AddressPart.POSTCODE }
        val postcodeInvalid = format?.postcodePattern != null && postcode != null && !format.isPostcode(postcode.value)
        if (postcodeInvalid) notes += NOTE_POSTCODE_SHAPE

        val oneBlock = isOneBlock(labeled.lines)
        if (!oneBlock) notes += NOTE_NOT_ONE_BLOCK

        val nameLines = labeled.parts.filter { it.part == AddressPart.RECIPIENT_NAME || it.part == AddressPart.ORGANISATION }
        val nameMissing = expectedNames.isNotEmpty() && expectedNames.none { name -> found(name, nameLines) }
        if (nameMissing) notes += NOTE_NAME_NOT_FOUND

        val countryUnknown = format == null
        if (countryUnknown) notes += NOTE_COUNTRY_UNKNOWN

        fun checks(p: LabeledPart): List<Check> = buildList {
            if (countryUnknown) add(Check.Cap(ConfidenceCombiner.MEDIUM, NOTE_COUNTRY_UNKNOWN, blocking = false))
            if (missing.isNotEmpty()) add(Check.Cap(ConfidenceCombiner.Caps.INCONSISTENT, "missing", blocking = false))
            if (!oneBlock) add(Check.Cap(ConfidenceCombiner.Caps.INCONSISTENT, NOTE_NOT_ONE_BLOCK, blocking = false))
            if (postcodeInvalid && p.part == AddressPart.POSTCODE) add(Check.Cap(ConfidenceCombiner.Caps.INVALID, NOTE_POSTCODE_SHAPE))
            if (nameMissing && (p.part == AddressPart.RECIPIENT_NAME || p.part == AddressPart.ORGANISATION)) {
                add(Check.Cap(ConfidenceCombiner.Caps.ROLE_MISMATCH, NOTE_NAME_NOT_FOUND, blocking = false))
            }
        }

        fun value(p: LabeledPart) = AddressPartValue(
            p.value, p.lineIdx, labeled.lines.getOrNull(p.lineIdx)?.bounds, ConfidenceCombiner.combine(p.ai, checks(p)).final,
        )

        fun first(part: AddressPart) = labeled.parts.firstOrNull { it.part == part }?.let(::value)

        return PostalAddress(
            lines = labeled.lines.map { it.text },
            page = page,
            recipientNames = labeled.parts.filter { it.part == AddressPart.RECIPIENT_NAME }.map(::value),
            salutation = first(AddressPart.SALUTATION),
            organisation = first(AddressPart.ORGANISATION),
            department = first(AddressPart.DEPARTMENT),
            careOf = first(AddressPart.CARE_OF),
            attention = first(AddressPart.ATTENTION),
            street = first(AddressPart.STREET),
            houseNumber = first(AddressPart.HOUSE_NUMBER),
            addressExtra = first(AddressPart.ADDRESS_EXTRA),
            postcode = first(AddressPart.POSTCODE),
            city = first(AddressPart.CITY),
            region = first(AddressPart.REGION),
            countryIso2 = first(AddressPart.COUNTRY),
            poBox = first(AddressPart.PO_BOX),
            packstation = first(AddressPart.PACKSTATION),
            formatId = format?.iso2,
            verified = format != null && missing.isEmpty() && !postcodeInvalid && oneBlock,
            notes = notes,
        )
    }

    /** A street is also satisfied by a post office box or a locker: neither has a street. */
    private fun isPresent(required: AddressPart, present: Set<AddressPart>): Boolean = when (required) {
        AddressPart.STREET -> AddressPart.STREET in present || AddressPart.PO_BOX in present || AddressPart.PACKSTATION in present
        else -> required in present
    }

    private fun isOneBlock(lines: List<AddressLine>): Boolean =
        lines.zipWithNext().all { (a, b) -> b.centerY - a.centerY <= AddressShapes.MAX_LINE_GAP }

    /** The expected name is, folded, in a name line of the address (as a quote of it, tolerating OCR noise). */
    private fun found(name: String, nameLines: List<LabeledPart>): Boolean {
        val text = nameLines.joinToString("\n") { it.value }
        return text.isNotBlank() && (AddressFormat.compact(name) in AddressFormat.compact(text) || QuoteVerifier.verify(name, text) != null)
    }

    companion object {
        const val NOTE_POSTCODE_SHAPE = "postcode_shape"
        const val NOTE_NOT_ONE_BLOCK = "not_one_block"
        const val NOTE_NAME_NOT_FOUND = "addressee_not_in_names"
        const val NOTE_COUNTRY_UNKNOWN = "country_unknown"
    }
}
