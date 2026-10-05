package com.postsaimanager.core.domain.extraction.address

import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.model.AddressPart
import com.postsaimanager.core.model.AddressPartValue
import com.postsaimanager.core.model.PostalAddress
import com.postsaimanager.core.model.TextBounds

/** One stored row of a structured address: the slot key (`addressee.street`), the value, and where it was read. */
class AddressRow(val key: String, val value: String, val confidence: Float, val page: Int, val bbox: TextBounds?, val role: PartyRole)

/**
 * The one owner of how a [PostalAddress] becomes stored rows: the slot keys (`addressee.` or `sender.` plus a part's key), which part
 * feeds which row, and the raw-lines row that keeps the whole block. The existing `sender` and `addressee` rows (the party names)
 * are unrelated and unchanged.
 *
 * A post office box (`po_box`) and a locker (`packstation`) each have their own row. Every row's label is rendered from its key
 * (`SlotLabels` in the documents feature); a new part needs a label there.
 */
object AddressRows {

    /**
     * [FieldProvenance.origin][com.postsaimanager.core.model.FieldProvenance.origin] of an address part stored before verification was
     * recorded: it may belong to an address that failed its checks, so it is not shown ([isShown]).
     */
    const val ORIGIN = "ADDRESS"

    /** The origin of a part of an address that passed [PostalAddress.verified]: the only kind that is stored now and shown. */
    const val ORIGIN_VERIFIED = "ADDRESS_VERIFIED"

    /** Whether a stored address row with this [origin] is shown: not when it is a legacy, possibly unverified one ([ORIGIN]). */
    fun isShown(origin: String?): Boolean = origin != ORIGIN

    /** The suffix of the row that holds the printed lines of the whole block. */
    const val RAW = "raw"

    /** The row names' prefix of [role], or null for a role that has no structured address. */
    fun prefixOf(role: PartyRole): String? = when (role) {
        PartyRole.ADDRESSEE -> "addressee."
        PartyRole.SENDER -> "sender."
        else -> null
    }

    /** Whether [slotKey] is a row of a structured address. */
    fun isAddressKey(slotKey: String?): Boolean = slotKey != null && PartyRole.entries.mapNotNull(::prefixOf).any { slotKey.startsWith(it) }

    /** The rows of [address] for [role]: one per part present, then the raw lines. */
    fun rows(role: PartyRole, address: PostalAddress): List<AddressRow> {
        val prefix = prefixOf(role) ?: return emptyList()
        val rows = mutableListOf<AddressRow>()
        fun add(suffix: String, value: String, confidence: Float, bbox: TextBounds?) {
            if (value.isNotBlank()) rows += AddressRow(prefix + suffix, value.trim(), confidence, address.page, bbox, role)
        }
        fun single(part: AddressPart, v: AddressPartValue?) {
            if (v != null) add(part.key, v.value, v.confidence, v.bbox)
        }

        if (address.recipientNames.isNotEmpty()) {
            add(
                AddressPart.RECIPIENT_NAME.key, address.recipientNames.joinToString("; ") { it.value },
                address.recipientNames.minOf { it.confidence }, union(address.recipientNames.mapNotNull { it.bbox }),
            )
        }
        single(AddressPart.ORGANISATION, address.organisation)
        single(AddressPart.DEPARTMENT, address.department)
        single(AddressPart.CARE_OF, address.careOf)
        single(AddressPart.STREET, address.street)
        single(AddressPart.HOUSE_NUMBER, address.houseNumber)
        single(AddressPart.ADDRESS_EXTRA, address.addressExtra)
        single(AddressPart.POSTCODE, address.postcode)
        single(AddressPart.CITY, address.city)
        single(AddressPart.REGION, address.region)
        single(AddressPart.COUNTRY, address.countryIso2)
        single(AddressPart.PO_BOX, address.poBox)
        single(AddressPart.PACKSTATION, address.packstation)
        add(RAW, address.lines.joinToString("\n"), address.confidence, union(address.parts.mapNotNull { it.second.bbox }))
        return rows
    }

    private fun union(boxes: List<TextBounds>): TextBounds? =
        if (boxes.isEmpty()) null else TextBounds(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
}
