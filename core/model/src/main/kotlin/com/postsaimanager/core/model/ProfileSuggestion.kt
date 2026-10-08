package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/** Which detail of an organisation profile a [ProfileSuggestion] offers. */
@Serializable
enum class SuggestionField { ADDRESS, PHONE, EMAIL, WEBSITE, IBAN }

/** Where a suggestion stands: waiting for the user, or dismissed (kept so a re-reading does not offer it again). */
@Serializable
enum class SuggestionStatus { PENDING, DISMISSED }

/**
 * A value a letter showed for an organisation profile, offered to the user and never written by itself: the letter's postal address,
 * a general phone number, e-mail address, website or bank account. It is offered only for a field the profile has empty, with the
 * letter it came from. [value] is the text as the letter printed it; an [SuggestionField.ADDRESS] is [PostalValue] encoded.
 */
@Serializable
data class ProfileSuggestion(
    val id: String,
    val profileId: String,
    val field: SuggestionField,
    val value: String,
    val sourceDocumentId: String,
    val createdAt: Long,
    val status: SuggestionStatus = SuggestionStatus.PENDING,
)

/**
 * A postal address as the profile stores it (street, postcode, city, country), and how it is kept inside a suggestion's single text:
 * one part per line, in that order. Structure only; no part is interpreted.
 */
data class PostalValue(
    val street: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val country: String? = null,
) {
    val isEmpty: Boolean get() = listOf(street, postalCode, city, country).all { it.isNullOrBlank() }

    /** The four parts, one per line. */
    fun encode(): String = listOf(street, postalCode, city, country).joinToString("\n") { it?.trim().orEmpty() }

    /** One printable line: "Street 1, 12345 City, Country". */
    fun oneLine(): String = listOfNotNull(
        street?.takeIf { it.isNotBlank() },
        listOfNotNull(postalCode?.takeIf { it.isNotBlank() }, city?.takeIf { it.isNotBlank() }).joinToString(" ").takeIf { it.isNotBlank() },
        country?.takeIf { it.isNotBlank() },
    ).joinToString(", ") { it.trim() }

    companion object {
        fun decode(text: String): PostalValue {
            val parts = text.split("\n")
            fun part(i: Int) = parts.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
            return PostalValue(part(0), part(1), part(2), part(3))
        }
    }
}
