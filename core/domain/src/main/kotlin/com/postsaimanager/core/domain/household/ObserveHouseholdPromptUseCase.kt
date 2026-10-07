package com.postsaimanager.core.domain.household

import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.model.HouseholdRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/**
 * Whether to gently ask the user to add themselves and their family, and as what the editor should open.
 *
 * The person chips of the document list only ever show for the household (Me and the members), so with no "Me" a letter for the user
 * can never be tagged. The prompt is a one-time card: it shows while there is no "Me", at least one document exists (an empty app has
 * nothing to tag yet) and the user has not dismissed it; it goes away by itself once a "Me" exists. The role to preset is
 * [HouseholdRole.SELF] while there is no "Me", which is the only time the card shows; the family members are added from Profiles
 * afterwards (the editor then opens as a member).
 *
 * Emits the role to preset, or null for no card.
 */
class ObserveHouseholdPromptUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val documents: DocumentRepository,
    private val preferences: UserPreferencesRepository,
) {

    operator fun invoke(): Flow<HouseholdRole?> = combine(
        profiles.getProfiles(),
        documents.getDocuments(),
        preferences.getUserPreferences(),
    ) { all, docs, prefs ->
        val hasMe = all.any { it.isSelf }
        if (prefs.householdPromptDismissed || hasMe || docs.isEmpty()) null else HouseholdRole.SELF
    }.distinctUntilChanged()
}

/** "Not now" on the household card: it is never shown again. */
class DismissHouseholdPromptUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
) {
    suspend operator fun invoke() {
        preferences.setHouseholdPromptDismissed(true)
    }
}
