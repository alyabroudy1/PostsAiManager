package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.FormDataKey
import com.postsaimanager.core.model.Profile

/**
 * The one owner of how a [com.postsaimanager.core.model.FormDataKey.profileColumn] name maps to a [Profile] property,
 * for reading ([PersonDataSource]) and writing ([RememberDetailUseCase]).
 */
object ProfileColumns {

    /**
     * Keys whose value the profile owns although the registry does not name a column for them yet: the birth date lives
     * in [Profile.birthDate] (one owner, with a date picker), so `birth_date` must never be stored as a fact too.
     * Drop an entry here once `FormDataKeys` names the column itself.
     */
    private val IMPLICIT_COLUMNS: Map<String, String> = mapOf("birth_date" to "birthDate")

    /** The profile column that holds [key], or null when it is a saved fact. */
    fun columnOf(key: FormDataKey): String? = key.profileColumn ?: IMPLICIT_COLUMNS[key.id]

    /** Every column a data key may name. */
    val SUPPORTED: Set<String> = setOf("name", "street", "postalCode", "city", "country", "phone", "email", "birthDate")

    /** The non-blank value of [column] on [profile], or null. */
    fun read(profile: Profile, column: String): String? {
        val value = when (column) {
            "name" -> profile.name
            "street" -> profile.street
            "postalCode" -> profile.postalCode
            "city" -> profile.city
            "country" -> profile.country
            "phone" -> profile.phone
            "email" -> profile.email
            "birthDate" -> profile.birthDate
            else -> null
        }
        return value?.takeIf { it.isNotBlank() }
    }

    /** [profile] with [column] set to [value], or null when [column] is unknown. */
    fun write(profile: Profile, column: String, value: String, now: Long): Profile? {
        val updated = when (column) {
            "name" -> profile.copy(name = value)
            "street" -> profile.copy(street = value)
            "postalCode" -> profile.copy(postalCode = value)
            "city" -> profile.copy(city = value)
            "country" -> profile.copy(country = value)
            "phone" -> profile.copy(phone = value)
            "email" -> profile.copy(email = value)
            "birthDate" -> profile.copy(birthDate = value)
            else -> return null
        }
        return updated.copy(modifiedAt = now)
    }
}
