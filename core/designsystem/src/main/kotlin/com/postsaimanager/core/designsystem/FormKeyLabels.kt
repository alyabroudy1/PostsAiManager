package com.postsaimanager.core.designsystem

import androidx.annotation.StringRes

/**
 * The string resource of each `FormDataKeys` id (what a form can ask for), shared by every screen that names a
 * person's detail: the profile's saved details and the form-fill chat. Data holds ids, never English; a new key needs
 * one line here and one string (a test in feature/profiles fails until every registry key has both).
 */
object FormKeyLabels {

    private val labels: Map<String, Int> = mapOf(
        "full_name" to R.string.form_key_full_name,
        "given_name" to R.string.form_key_given_name,
        "family_name" to R.string.form_key_family_name,
        "birth_date" to R.string.form_key_birth_date,
        "birth_place" to R.string.form_key_birth_place,
        "nationality" to R.string.form_key_nationality,
        "gender" to R.string.form_key_gender,
        "address" to R.string.form_key_address,
        "street" to R.string.form_key_street,
        "postcode" to R.string.form_key_postcode,
        "city" to R.string.form_key_city,
        "country" to R.string.form_key_country,
        "phone" to R.string.form_key_phone,
        "mobile" to R.string.form_key_mobile,
        "email" to R.string.form_key_email,
        "health_insurer" to R.string.form_key_health_insurer,
        "insurance_no" to R.string.form_key_insurance_no,
        "tax_id" to R.string.form_key_tax_id,
        "iban" to R.string.form_key_iban,
        "account_holder" to R.string.form_key_account_holder,
        "bank_name" to R.string.form_key_bank_name,
        "school" to R.string.form_key_school,
        "school_class" to R.string.form_key_school_class,
        "employer" to R.string.form_key_employer,
        "occupation" to R.string.form_key_occupation,
        "allergies" to R.string.form_key_allergies,
        "swim_level" to R.string.form_key_swim_level,
        "today_date" to R.string.form_key_today_date,
        "today_place" to R.string.form_key_today_place,
        "signature" to R.string.form_key_signature,
    )

    /** Every key id that has a label. */
    val keyIds: Set<String> get() = labels.keys

    /** The label's resource for [keyId], or null for a key without one. */
    @StringRes
    fun of(keyId: String): Int? = labels[keyId]
}
