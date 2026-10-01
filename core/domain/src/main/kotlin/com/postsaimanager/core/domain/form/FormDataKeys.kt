package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.FormDataKey
import com.postsaimanager.core.model.FormValueKind

/**
 * The personal details a form can ask for: the one owner of that vocabulary (data, not rules).
 *
 * The model and the embedding ranker match a printed label to a key through [FormDataKey.description]
 * (English content descriptions, never shown to users; labels come from string resources in the UI).
 * A key whose [FormDataKey.profileColumn] is set reads and writes that [com.postsaimanager.core.model.Profile]
 * property; every other key is a [com.postsaimanager.core.model.ProfileFact]. Adding a key is a data change here
 * plus its string resource.
 */
object FormDataKeys {
    val FULL_NAME = FormDataKey("full_name", FormValueKind.NAME, "the person's full name", profileColumn = "name")
    val GIVEN_NAME = FormDataKey("given_name", FormValueKind.NAME, "the person's first name or given name")
    val FAMILY_NAME = FormDataKey("family_name", FormValueKind.NAME, "the person's last name, surname or family name")
    val BIRTH_DATE = FormDataKey("birth_date", FormValueKind.DATE, "the person's date of birth", profileColumn = "birthDate")
    val BIRTH_PLACE = FormDataKey("birth_place", FormValueKind.TEXT, "the person's place of birth")
    val NATIONALITY = FormDataKey("nationality", FormValueKind.TEXT, "the person's nationality or citizenship")
    val GENDER = FormDataKey("gender", FormValueKind.TEXT, "the person's gender or sex")
    val ADDRESS = FormDataKey("address", FormValueKind.ADDRESS, "the person's full postal address", reconfirmAfterMonths = 12)
    val STREET = FormDataKey("street", FormValueKind.TEXT, "the street and house number of the person's address", reconfirmAfterMonths = 12, profileColumn = "street")
    val POSTCODE = FormDataKey("postcode", FormValueKind.POSTCODE, "the postcode or zip code of the person's address", reconfirmAfterMonths = 12, profileColumn = "postalCode")
    val CITY = FormDataKey("city", FormValueKind.TEXT, "the city or town of the person's address", reconfirmAfterMonths = 12, profileColumn = "city")
    val COUNTRY = FormDataKey("country", FormValueKind.TEXT, "the country of the person's address", profileColumn = "country")
    val PHONE = FormDataKey("phone", FormValueKind.PHONE, "the person's telephone number", reconfirmAfterMonths = 12, profileColumn = "phone")
    val MOBILE = FormDataKey("mobile", FormValueKind.PHONE, "the person's mobile phone number", reconfirmAfterMonths = 12)
    val EMAIL = FormDataKey("email", FormValueKind.EMAIL, "the person's e-mail address", reconfirmAfterMonths = 12, profileColumn = "email")
    val HEALTH_INSURER = FormDataKey("health_insurer", FormValueKind.TEXT, "the name of the person's health insurance company", sensitive = true, reconfirmAfterMonths = 12)
    val INSURANCE_NO = FormDataKey("insurance_no", FormValueKind.TEXT, "the person's health insurance number", sensitive = true)
    val TAX_ID = FormDataKey("tax_id", FormValueKind.TEXT, "the person's tax identification number", sensitive = true)
    val IBAN = FormDataKey("iban", FormValueKind.IBAN, "the IBAN of the bank account for payments or direct debit", sensitive = true)
    val ACCOUNT_HOLDER = FormDataKey("account_holder", FormValueKind.NAME, "the name of the bank account holder")
    val BANK_NAME = FormDataKey("bank_name", FormValueKind.TEXT, "the name of the bank")
    val SCHOOL = FormDataKey("school", FormValueKind.TEXT, "the school or kindergarten the person attends", reconfirmAfterMonths = 12)
    val SCHOOL_CLASS = FormDataKey("school_class", FormValueKind.TEXT, "the person's school class or grade", reconfirmAfterMonths = 12)
    val EMPLOYER = FormDataKey("employer", FormValueKind.TEXT, "the person's employer or workplace", reconfirmAfterMonths = 12)
    val OCCUPATION = FormDataKey("occupation", FormValueKind.TEXT, "the person's job or occupation", reconfirmAfterMonths = 12)
    val ALLERGIES = FormDataKey("allergies", FormValueKind.TEXT, "allergies, illnesses, medication or health notes about the person", sensitive = true, reconfirmAfterMonths = 12)
    val SWIM_LEVEL = FormDataKey("swim_level", FormValueKind.TEXT, "the person's swimming badge or swimming ability", reconfirmAfterMonths = 12)
    val TODAY_DATE = FormDataKey("today_date", FormValueKind.DATE, "the date of filling in or signing the form")
    val TODAY_PLACE = FormDataKey("today_place", FormValueKind.TEXT, "the place where the form is filled in or signed")
    val SIGNATURE = FormDataKey("signature", FormValueKind.TEXT, "a handwritten signature")

    val ALL: List<FormDataKey> = listOf(
        FULL_NAME, GIVEN_NAME, FAMILY_NAME, BIRTH_DATE, BIRTH_PLACE, NATIONALITY, GENDER,
        ADDRESS, STREET, POSTCODE, CITY, COUNTRY, PHONE, MOBILE, EMAIL,
        HEALTH_INSURER, INSURANCE_NO, TAX_ID, IBAN, ACCOUNT_HOLDER, BANK_NAME,
        SCHOOL, SCHOOL_CLASS, EMPLOYER, OCCUPATION, ALLERGIES, SWIM_LEVEL,
        TODAY_DATE, TODAY_PLACE, SIGNATURE,
    )

    private val byId: Map<String, FormDataKey> = ALL.associateBy { it.id }

    fun of(id: String?): FormDataKey? = id?.let(byId::get)

    /** Whether a remembered value for [id] is sensitive (masked in the UI, never in the all-documents chat). */
    fun isSensitive(id: String?): Boolean = of(id)?.sensitive == true
}
