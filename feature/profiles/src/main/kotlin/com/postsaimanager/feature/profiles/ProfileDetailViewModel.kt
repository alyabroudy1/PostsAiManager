package com.postsaimanager.feature.profiles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.result.map
import com.postsaimanager.core.domain.contacts.ObserveOrganisationContactsUseCase
import com.postsaimanager.core.domain.contacts.OrganisationContacts
import com.postsaimanager.core.domain.contacts.SetHouseholdRoleUseCase
import com.postsaimanager.core.domain.form.ForgetDetailUseCase
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.form.ObserveSavedDetailsUseCase
import com.postsaimanager.core.domain.form.ProfileColumns
import com.postsaimanager.core.domain.form.RememberDetailUseCase
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.FormDataKey
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileFact
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.Relationship
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Edits one profile (name, kind, household role, relationship, birth date, address, contact, the sensitive flag) and manages
 * its "saved details". The profile fields are a draft until [save]; saved details are written at once (they are separate
 * rows, each with its own undo). The household role is applied on save through [SetHouseholdRoleUseCase], the one owner of
 * its rules. An organisation also shows its contacts, read-only.
 */
@HiltViewModel
class ProfileDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val setHouseholdRole: SetHouseholdRoleUseCase,
    private val observeContacts: ObserveOrganisationContactsUseCase,
    private val observeDetails: ObserveSavedDetailsUseCase,
    private val rememberDetail: RememberDetailUseCase,
    private val forgetDetail: ForgetDetailUseCase,
) : ViewModel() {

    private val requestedId: String = savedState.get<String>(ARG_PROFILE_ID) ?: NEW
    private val isNew = requestedId == NEW
    private val profileId: String = if (isNew) UUID.randomUUID().toString() else requestedId

    private val draft = MutableStateFlow<Profile?>(null)

    /** The profile as stored (null for a new one): the role the editor started from. */
    private var stored: Profile? = null
    private val loaded = MutableStateFlow(isNew)
    private val notFound = MutableStateFlow(false)
    private val finished = MutableStateFlow(false)

    private val _message = MutableStateFlow<String?>(null)

    /** A one-off error for a snackbar; [consumeMessage] clears it. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _removed = MutableStateFlow<ProfileFact?>(null)

    /** The detail just removed, offered for undo; [consumeRemoved] clears it. */
    val removed: StateFlow<ProfileFact?> = _removed.asStateFlow()

    val uiState: StateFlow<ProfileDetailUiState> = combine(
        draft,
        loaded,
        notFound,
        finished,
        if (isNew) flowOf(emptyList()) else observeDetails(profileId),
    ) { current, isLoaded, missing, done, facts -> ProfileDetailUiState(current, isLoaded, missing, done, facts, isNew) }
        .combine(profiles.getProfiles()) { state, all ->
            state.copy(
                selfTaken = all.any { it.isSelf && it.id != profileId },
                selfLocked = all.any { it.isSelf && it.id == profileId },
            )
        }
        .combine(if (isNew) flowOf(OrganisationContacts(null, emptyList())) else observeContacts(profileId)) { state, found ->
            state.copy(contacts = found)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileDetailUiState(isNew = isNew))

    init {
        if (isNew) {
            val now = System.currentTimeMillis()
            draft.value = Profile(
                id = profileId, householdRole = HouseholdRole.MEMBER, name = "", createdAt = now, modifiedAt = now,
            )
        } else {
            viewModelScope.launch {
                when (val result = profiles.getProfileById(profileId)) {
                    is PamResult.Success -> {
                        stored = result.data
                        draft.value = result.data
                    }
                    is PamResult.Error -> notFound.value = true
                }
                loaded.value = true
            }
        }
    }

    fun update(transform: (Profile) -> Profile) {
        draft.update { it?.let(transform) }
    }

    /** Switching kind keeps the draft consistent: an organisation has no household role, relationship or birth date. */
    fun setKind(kind: ProfileKind) = update {
        if (kind == ProfileKind.ORGANISATION) {
            it.copy(kind = kind, householdRole = null, relationship = null, birthDate = null)
        } else {
            it.copy(kind = kind)
        }
    }

    /** Joins or leaves the household in the draft; only a member has a relationship. Applied on [save]. */
    fun setRole(role: HouseholdRole?) = update {
        it.copy(householdRole = role, relationship = it.relationship.takeIf { role == HouseholdRole.MEMBER })
    }

    fun setRelationship(relationship: Relationship?) = update { it.copy(relationship = relationship) }

    fun save() {
        val current = draft.value ?: return
        if (current.name.isBlank()) return
        // The fields are saved with the role the profile already has; a changed role then goes through the use case.
        val before = stored
        val clean = current.copy(
            name = current.name.trim(),
            street = current.street.cleaned(),
            postalCode = current.postalCode.cleaned(),
            city = current.city.cleaned(),
            phone = current.phone.cleaned(),
            email = current.email.cleaned(),
            householdRole = before?.householdRole,
            relationship = before?.relationship,
            modifiedAt = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            val saved = if (isNew) profiles.createProfile(clean).map { } else profiles.updateProfile(clean)
            val roleChanged = current.householdRole != clean.householdRole || current.relationship != clean.relationship
            val result = if (saved is PamResult.Success && roleChanged) {
                setHouseholdRole(profileId, current.householdRole, current.relationship)
            } else {
                saved
            }
            when (result) {
                is PamResult.Success -> finished.value = true
                is PamResult.Error -> _message.value = result.error.userMessage
            }
        }
    }

    /** Keys the "Add detail" picker offers: the registry minus profile fields and details already saved. */
    fun availableKeys(saved: List<ProfileFact>): List<FormDataKey> {
        val taken = saved.map { it.key }.toSet()
        return FormDataKeys.ALL.filter { ProfileColumns.columnOf(it) == null && it.id !in taken }
    }

    /** Saves [value] for [keyId] (adding or editing; the person typed it, so the source is USER). */
    fun saveDetail(keyId: String, value: String) {
        viewModelScope.launch {
            val result = rememberDetail(profileId, keyId, value, FactSource.USER)
            if (result is PamResult.Error) _message.value = result.error.userMessage
        }
    }

    fun deleteDetail(fact: ProfileFact) {
        viewModelScope.launch {
            when (val result = forgetDetail(profileId, fact.key)) {
                is PamResult.Success -> _removed.value = fact
                is PamResult.Error -> _message.value = result.error.userMessage
            }
        }
    }

    /** Puts the last removed detail back, as it was. */
    fun undoDelete() {
        val fact = _removed.value ?: return
        _removed.value = null
        viewModelScope.launch {
            val result = rememberDetail(profileId, fact.key, fact.value, fact.source, fact.sourceDocumentId)
            if (result is PamResult.Error) _message.value = result.error.userMessage
        }
    }

    fun consumeRemoved() {
        _removed.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val ARG_PROFILE_ID = "profileId"

        /** The route argument that means "create a new person". */
        const val NEW = "new"
    }
}

data class ProfileDetailUiState(
    /** The profile being edited, or null until loaded (or when it no longer exists). */
    val draft: Profile? = null,
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    /** Saved, so the screen should close. */
    val finished: Boolean = false,
    val facts: List<ProfileFact> = emptyList(),
    val isNew: Boolean = false,
    /** Another profile is already "Me", so this one cannot be. */
    val selfTaken: Boolean = false,
    /** This stored profile is "Me": its role and kind cannot be changed. */
    val selfLocked: Boolean = false,
    /** The contacts of this profile when it is an organisation (empty otherwise). */
    val contacts: OrganisationContacts = OrganisationContacts(null, emptyList()),
) {
    val canSave: Boolean get() = draft?.name?.isNotBlank() == true
}
