package com.postsaimanager.core.domain.organisation

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.repository.ProfileSuggestionRepository
import com.postsaimanager.core.model.CustomDetail
import com.postsaimanager.core.model.PostalValue
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileSuggestion
import com.postsaimanager.core.model.SuggestionField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The one owner of "does this suggestion still apply": it does while the field it would fill is empty. */
object SuggestionRules {

    /** Whether [field] of [profile] has no value (the address: no street, postcode and city; the account: no own detail "IBAN"). */
    fun isEmpty(profile: Profile, field: SuggestionField): Boolean = when (field) {
        SuggestionField.ADDRESS -> profile.street.isNullOrBlank() && profile.postalCode.isNullOrBlank() && profile.city.isNullOrBlank()
        SuggestionField.PHONE -> profile.phone.isNullOrBlank()
        SuggestionField.EMAIL -> profile.email.isNullOrBlank()
        SuggestionField.WEBSITE -> profile.website.isNullOrBlank()
        SuggestionField.IBAN -> profile.customDetails.none { it.label.trim().equals(SuggestOrganisationDetailsUseCase.IBAN_LABEL, ignoreCase = true) }
    }

    /** The suggestions of [pending] that still apply to [profile] (its field is empty). */
    fun open(profile: Profile, pending: List<ProfileSuggestion>): List<ProfileSuggestion> = pending.filter { isEmpty(profile, it.field) }

    /** The oldest suggestion of each field of [open]: what "Accept all" accepts (one value per field). */
    fun oldestPerField(open: List<ProfileSuggestion>): List<ProfileSuggestion> =
        open.groupBy { it.field }.map { (_, list) -> list.minBy { it.createdAt } }

    /** [profile] with [value] written to [field] (the address: [PostalValue] encoded; the account: an own detail "IBAN"). */
    fun apply(profile: Profile, field: SuggestionField, value: String, now: Long): Profile {
        val clean = value.trim()
        val written = when (field) {
            SuggestionField.PHONE -> profile.copy(phone = clean)
            SuggestionField.EMAIL -> profile.copy(email = clean)
            SuggestionField.WEBSITE -> profile.copy(website = clean)
            SuggestionField.ADDRESS -> PostalValue.decode(value).let {
                profile.copy(street = it.street, postalCode = it.postalCode, city = it.city, country = it.country ?: profile.country)
            }
            SuggestionField.IBAN -> profile.copy(
                customDetails = profile.customDetails.filterNot { it.label.trim().equals(SuggestOrganisationDetailsUseCase.IBAN_LABEL, ignoreCase = true) } +
                    CustomDetail(SuggestOrganisationDetailsUseCase.IBAN_LABEL, clean),
            )
        }
        return written.copy(modifiedAt = now)
    }
}

/** A suggestion with the title of the letter it came from (blank when that letter is gone). */
data class SuggestionView(val suggestion: ProfileSuggestion, val letterTitle: String)

/** The pending suggestions of an organisation profile, each with its source letter's title. */
class ObserveProfileSuggestionsUseCase @Inject constructor(
    private val suggestions: ProfileSuggestionRepository,
    private val documents: DocumentRepository,
) {
    operator fun invoke(profileId: String): Flow<List<SuggestionView>> = suggestions.observePending(profileId).map { pending ->
        pending.map { s -> SuggestionView(s, (documents.getDocumentById(s.sourceDocumentId) as? PamResult.Success)?.data?.title.orEmpty()) }
    }
}

/**
 * The user accepts a suggestion: its value (or the text they edited it to) is written to the profile's field, and the other suggestions
 * for that field go. What is written is the user's value from then on: a later reading only fills what is empty. [acceptAll] accepts the
 * oldest suggestion of every field.
 */
class AcceptProfileSuggestionUseCase @Inject constructor(
    private val suggestions: ProfileSuggestionRepository,
    private val profiles: ProfileRepository,
) {
    suspend operator fun invoke(suggestionId: String, editedValue: String? = null, now: Long = System.currentTimeMillis()): PamResult<Unit> {
        val suggestion = suggestions.get(suggestionId) ?: return PamResult.Error(PamError.FileNotFound(path = suggestionId))
        val value = (editedValue ?: suggestion.value).trim()
        if (value.isEmpty()) return PamResult.Error(PamError.ValidationError("value", "a suggestion needs a value"))
        val profile = when (val loaded = profiles.getProfileById(suggestion.profileId)) {
            is PamResult.Success -> loaded.data
            is PamResult.Error -> return PamResult.Error(loaded.error)
        }
        return when (val saved = profiles.updateProfile(SuggestionRules.apply(profile, suggestion.field, value, now))) {
            is PamResult.Success -> {
                suggestions.clearPending(profile.id, suggestion.field)
                PamResult.Success(Unit)
            }
            is PamResult.Error -> saved
        }
    }

    /** Accepts, for every field with a pending suggestion that still applies, the oldest one. */
    suspend fun acceptAll(profileId: String, pending: List<ProfileSuggestion>, now: Long = System.currentTimeMillis()): PamResult<Unit> {
        var profile = when (val loaded = profiles.getProfileById(profileId)) {
            is PamResult.Success -> loaded.data
            is PamResult.Error -> return PamResult.Error(loaded.error)
        }
        val chosen = SuggestionRules.oldestPerField(SuggestionRules.open(profile, pending))
        if (chosen.isEmpty()) return PamResult.Success(Unit)
        chosen.forEach { profile = SuggestionRules.apply(profile, it.field, it.value, now) }
        return when (val saved = profiles.updateProfile(profile)) {
            is PamResult.Success -> {
                chosen.forEach { suggestions.clearPending(profileId, it.field) }
                PamResult.Success(Unit)
            }
            is PamResult.Error -> saved
        }
    }
}

/** The user does not want a suggestion: it is kept as dismissed, so a re-reading does not offer the same value again. */
class DismissProfileSuggestionUseCase @Inject constructor(private val suggestions: ProfileSuggestionRepository) {
    suspend operator fun invoke(suggestionId: String): PamResult<Unit> {
        suggestions.dismiss(suggestionId)
        return PamResult.Success(Unit)
    }
}
