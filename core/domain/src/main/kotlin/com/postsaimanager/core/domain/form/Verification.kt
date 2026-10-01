package com.postsaimanager.core.domain.form

/** The outcome of checking an answer against the form: a normalized value, or why it was refused (so the question can be asked again with a hint). */
sealed interface Verification {
    data class Accepted(val value: String) : Verification

    data class Rejected(val reason: Rejection) : Verification
}

enum class Rejection { EMPTY, NOT_AN_OPTION, AMBIGUOUS_OPTION, NOT_A_DATE, NOT_A_PHONE, NOT_AN_EMAIL, NOT_AN_IBAN, BAD_IBAN_CHECKSUM, NOT_A_POSTCODE }
