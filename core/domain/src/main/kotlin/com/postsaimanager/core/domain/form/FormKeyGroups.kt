package com.postsaimanager.core.domain.form

/**
 * A family of related [FormDataKeys], with an English description for the model (never shown to users).
 *
 * The fallback classifier without an embedding model first scores a field against the groups, then only against the keys of its
 * best group(s): about 8 + 6 scores per field instead of every key.
 */
data class FormKeyGroup(val id: String, val description: String, val keyIds: List<String>)

/** The one owner of how the data keys are grouped (data, not rules). A key belongs to exactly one group. */
object FormKeyGroups {
    val ALL: List<FormKeyGroup> = listOf(
        FormKeyGroup(
            "name", "the name of a person (full name, first name or last name)",
            listOf("full_name", "given_name", "family_name"),
        ),
        FormKeyGroup(
            "personal", "personal facts about a person (date or place of birth, nationality, gender)",
            listOf("birth_date", "birth_place", "nationality", "gender"),
        ),
        FormKeyGroup(
            "contact", "how to reach a person (telephone, mobile phone or e-mail address)",
            listOf("phone", "mobile", "email"),
        ),
        FormKeyGroup(
            "address", "a postal address or one of its parts (street, postcode, city, country)",
            listOf("address", "street", "postcode", "city", "country"),
        ),
        FormKeyGroup(
            "bank", "bank, payment or tax details (IBAN, account holder, bank name, tax number)",
            listOf("iban", "account_holder", "bank_name", "tax_id"),
        ),
        FormKeyGroup(
            "health", "health details (health insurance, allergies, illnesses, medication, swimming ability)",
            listOf("health_insurer", "insurance_no", "allergies", "swim_level"),
        ),
        FormKeyGroup(
            "school_work", "school, kindergarten, work or occupation",
            listOf("school", "school_class", "employer", "occupation"),
        ),
        FormKeyGroup(
            "date_place_signature", "the date, place or signature at the end of the form",
            listOf("today_date", "today_place", "signature"),
        ),
    )
}
