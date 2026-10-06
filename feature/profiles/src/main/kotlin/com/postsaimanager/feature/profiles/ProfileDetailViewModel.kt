package com.postsaimanager.feature.profiles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.result.map
import com.postsaimanager.core.domain.form.ForgetDetailUseCase
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.form.ObserveSavedDetailsUseCase
import com.postsaimanager.core.domain.form.ProfileColumns
import com.postsaimanager.core.domain.document.people.QueueConcernedPeopleCheckUseCase
import com.postsaimanager.core.domain.form.RememberDetailUseCase
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.FormDataKey
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileFact
import com.postsaimanager.core.model.ProfileType
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
 * Edits one profile (name, type, relationship, birth date, address, contact, the sensitive flag) and manages its
 * "saved details". The profile fields are a draft until [save]; saved details are written at once (they are separate
 * rows, each with its own undo).
 */
@HiltViewModel
class ProfileDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val observeDetails: ObserveSavedDetailsUseCase,
    private val rememberDetail: RememberDetailUseCase,
    private val forgetDetail: ForgetDetailUseCase,
    private val queuePeopleCheck: QueueConcernedPeopleCheckUseCase,
) : ViewModel() {

    private val requestedId: String = savedState.get<String>(ARG_PROFILE_ID) ?: NEW
    private val isNew = requestedId == NEW
    private val profileId: String = if (isNew) UUID.randomUUID().toString() else requestedId

    private val draft = MutableStateFlow<Profile?>(null)
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
            state.copy(selfTaken = all.any { it.type == ProfileType.USER_SELF && it.id != profileId })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileDetailUiState(isNew = isNew))

    init {
        if (isNew) {
            val now = System.currentTimeMillis()
            draft.value = Profile(
                id = profileId, type = ProfileType.FAMILY_MEMBER, name = "", createdAt = now, modifiedAt = now,
            )
        } else {
            viewModelScope.launch {
                when (val result = profiles.getProfileById(profileId)) {
                    is PamResult.Success -> draft.value = result.data
                    is PamResult.Error -> notFound.value = true
                }
                loaded.value = true
            }
        }
    }

    fun update(transform: (Profile) -> Profile) {
        draft.update { it?.let(transform) }
    }

    /** Switching type keeps the draft consistent: only a family member has a relationship, an organisation no birth date. */
    fun setType(type: ProfileType) = update {
        it.copy(
            type = type,
            relationship = it.relationship.takeIf { type == ProfileType.FAMILY_MEMBER },
            birthDate = it.birthDate.takeIf { type != ProfileType.AUTHORITY },
        )
    }

    fun setRelationship(relationship: Relationship?) = update { it.copy(relationship = relationship) }

    fun save() {
        val current = draft.value ?: return
        if (current.name.isBlank()) return
        val clean = current.copy(
            name = current.name.trim(),
            street = current.street.cleaned(),
            postalCode = current.postalCode.cleaned(),
            city = current.city.cleaned(),
            phone = current.phone.cleaned(),
            email = current.email.cleaned(),
            modifiedAt = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            val result = if (isNew) profiles.createProfile(clean).map { } else profiles.updateProfile(clean)
            when (result) {
                is PamResult.Success -> {
                    // Documents already read are asked about a person added or renamed (only those whose text mentions the name).
                    runCatching { queuePeopleCheck(clean) }
                    finished.value = true
                }
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
) {
    val canSave: Boolean get() = draft?.name?.isNotBlank() == true
}
