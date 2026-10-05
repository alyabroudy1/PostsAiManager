package com.postsaimanager.core.domain.form

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.result.map
import com.postsaimanager.core.domain.repository.ProfileFactRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.FactSource
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject

/**
 * Remembers one detail of a person ("Remember for Ahmad"). The key must exist in [FormDataKeys] and the value must be
 * non-blank. A key that names a profile column ([com.postsaimanager.core.model.FormDataKey.profileColumn]) is written to
 * that column (the profile stays the one owner of it); every other key becomes a saved fact.
 */
class RememberDetailUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val facts: ProfileFactRepository,
) {

    suspend operator fun invoke(
        profileId: String,
        key: String,
        value: String,
        source: FactSource,
        sourceDocumentId: String? = null,
    ): PamResult<Unit> {
        val dataKey = FormDataKeys.of(key)
            ?: return invalid("key", "unknown detail \"$key\"")
        val clean = value.trim()
        if (clean.isEmpty()) return invalid("value", "must not be blank")

        val column = ProfileColumns.columnOf(dataKey)
            ?: return facts.upsert(profileId, key, clean, source, sourceDocumentId).map { }

        if (column == BIRTH_DATE_COLUMN && !isIsoDate(clean)) return invalid("value", "a birth date must be yyyy-MM-dd")
        val profile = when (val result = profiles.getProfileById(profileId)) {
            is PamResult.Success -> result.data
            is PamResult.Error -> return PamResult.Error(result.error)
        }
        val updated = ProfileColumns.write(profile, column, clean, System.currentTimeMillis())
            ?: return invalid("key", "unsupported profile column \"$column\"")
        return profiles.updateProfile(updated)
    }

    private fun invalid(field: String, reason: String) = PamResult.Error(PamError.ValidationError(field, reason))

    private fun isIsoDate(text: String): Boolean = try {
        LocalDate.parse(text)
        true
    } catch (_: DateTimeParseException) {
        false
    }

    private companion object {
        const val BIRTH_DATE_COLUMN = "birthDate"
    }
}
