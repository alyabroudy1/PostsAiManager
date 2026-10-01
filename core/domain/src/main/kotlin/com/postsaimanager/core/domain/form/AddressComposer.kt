package com.postsaimanager.core.domain.form

import com.postsaimanager.core.domain.extraction.address.AddressFormatRegistry
import com.postsaimanager.core.domain.extraction.address.AddressFormats
import com.postsaimanager.core.model.AddressPart

/**
 * Writes a person's address as one line in the order the country prints it (the `lineOrder` of its [AddressFormats] entry: street,
 * then postcode and city, or city then postcode, ...), from the person's street, postcode and city values. The country is the
 * form's when known, otherwise the one whose postcode shape the person's postcode has. An address with no street or no city is
 * not composed: half an address is never written on a form.
 */
class AddressComposer(private val formats: AddressFormatRegistry = AddressFormats.default) {

    /** [values] by [FormDataKeys] id. Null when the address is incomplete. */
    fun compose(values: Map<String, PersonValue>, countryIso2: String?): PersonValue? {
        val street = values[FormDataKeys.STREET.id]
        val city = values[FormDataKeys.CITY.id]
        val postcode = values[FormDataKeys.POSTCODE.id]
        if (street == null || city == null) return null
        val format = formats.of(countryIso2) ?: postcode?.let { formats.formats.firstOrNull { f -> f.isPostcode(it.value) } }
        val order = format?.lineOrder.orEmpty().mapNotNull {
            when (it) {
                AddressPart.STREET -> street
                AddressPart.POSTCODE -> postcode
                AddressPart.CITY -> city
                else -> null
            }?.let { v -> it to v.value.trim() }
        }.ifEmpty { listOfNotNull(AddressPart.STREET to street.value.trim(), postcode?.let { AddressPart.POSTCODE to it.value.trim() }, AddressPart.CITY to city.value.trim()) }

        val lines = ArrayList<String>()
        var i = 0
        while (i < order.size) {
            val (part, text) = order[i]
            val next = order.getOrNull(i + 1)
            val joinable = next != null && setOf(part, next.first) == setOf(AddressPart.POSTCODE, AddressPart.CITY)
            if (joinable) {
                lines += "$text ${next!!.second}"
                i += 2
            } else {
                lines += text
                i++
            }
        }
        val used = listOfNotNull(street, city, postcode)
        return PersonValue(lines.joinToString(", "), used.first().source, used.minOf { it.updatedAt }, sensitive = false)
    }
}
