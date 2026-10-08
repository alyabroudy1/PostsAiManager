package com.postsaimanager.feature.profiles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.result.map
import com.postsaimanager.core.domain.applock.ExternalFlowGuard
import com.postsaimanager.core.domain.applock.ExternalFlowToken
import com.postsaimanager.core.domain.contacts.AddContactUseCase
import com.postsaimanager.core.domain.contacts.ConfirmContactUseCase
import com.postsaimanager.core.domain.contacts.DeleteContactUseCase
import com.postsaimanager.core.domain.contacts.DiscardContactUseCase
import com.postsaimanager.core.domain.organisation.AcceptProfileSuggestionUseCase
import com.postsaimanager.core.domain.organisation.DismissProfileSuggestionUseCase
import com.postsaimanager.core.domain.organisation.ObserveProfileSuggestionsUseCase
import com.postsaimanager.core.domain.organisation.SuggestionRules
import com.postsaimanager.core.domain.organisation.SuggestionView
import com.postsaimanager.core.model.CustomDetails
import com.postsaimanager.core.domain.contacts.MergeContactsUseCase
import com.postsaimanager.core.domain.contacts.MoveContactUseCase
import com.postsaimanager.core.domain.contacts.ObserveOrganisationContactsUseCase
import com.postsaimanager.core.domain.contacts.OrganisationContacts
import com.postsaimanager.core.domain.contacts.SetContactActiveUseCase
import com.postsaimanager.core.domain.contacts.SetHouseholdRoleUseCase
import com.postsaimanager.core.domain.contacts.UpdateContactUseCase
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.designsystem.component.TimelineCaseInput
import com.postsaimanager.core.designsystem.component.TimelinePresenter
import com.postsaimanager.core.designsystem.component.TimelineUi
import com.postsaimanager.core.domain.timeline.ObserveTimelineForOrganisationUseCase
import com.postsaimanager.core.domain.timeline.ObserveTimelineForPersonUseCase
import com.postsaimanager.core.domain.timeline.RenameCaseUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    private val updateContact: UpdateContactUseCase,
    private val setContactActive: SetContactActiveUseCase,
    private val mergeContacts: MergeContactsUseCase,
    private val moveContact: MoveContactUseCase,
    private val deleteContact: DeleteContactUseCase,
    private val confirmContact: ConfirmContactUseCase,
    private val addContact: AddContactUseCase,
    private val discardContact: DiscardContactUseCase,
    private val observeSuggestions: ObserveProfileSuggestionsUseCase,
    private val acceptSuggestion: AcceptProfileSuggestionUseCase,
    private val dismissSuggestion: DismissProfileSuggestionUseCase,
    private val externalFlowGuard: ExternalFlowGuard,
    private val observePersonTimeline: ObserveTimelineForPersonUseCase,
    private val observeOrganisationTimeline: ObserveTimelineForOrganisationUseCase,
    private val renameCase: RenameCaseUseCase,
) : ViewModel() {

    /** The contact the organisation page was opened for (the letter's contact chip): scrolled into view once. */
    private val focusContactId: String? = savedState.get<String>(ARG_CONTACT_ID)?.takeIf { it.isNotBlank() }

    /** The matter the page was opened for (a letter's "Part of" row): starts expanded. */
    private val focusCaseId: String? = savedState.get<String>(ARG_CASE_ID)?.takeIf { it.isNotBlank() }

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
            state.copy(contacts = found, focusContactId = focusContactId)
        }
        .combine(profiles.getProfilesByKind(ProfileKind.ORGANISATION)) { state, organisations ->
            state.copy(otherOrganisations = organisations.filter { it.id != profileId }.sortedBy { it.name.lowercase() })
        }
        .combine(timeline()) { state, timeline -> state.copy(timeline = timeline, focusCaseId = focusCaseId) }
        .combine(if (isNew) flowOf(emptyList()) else observeSuggestions(profileId)) { state, found -> state.copy(suggestions = found) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileDetailUiState(isNew = isNew))

    /**
     * This profile's timeline as the screen draws it: a person's (or Me's) matters, or for an organisation the matters of every
     * household person. The profile's kind picks the use case; names come from the profile list.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun timeline(): Flow<TimelineUi> {
        if (isNew) return flowOf(TimelineUi.EMPTY)
        return profiles.getProfiles().flatMapLatest { all ->
            val organisation = all.firstOrNull { it.id == profileId }?.kind == ProfileKind.ORGANISATION
            val names = all.associate { it.id to it.name }
            val source = if (organisation) observeOrganisationTimeline(profileId) else observePersonTimeline(profileId)
            source.map { timeline ->
                TimelinePresenter.present(
                    cases = timeline.cases.map { TimelineCaseInput(it.case, it.events) },
                    looseEvents = timeline.looseEvents,
                    organisationName = names::get,
                    personName = names::get,
                    forOrganisation = organisation,
                )
            }
        }
    }

    init {
        if (isNew) {
            val now = System.currentTimeMillis()
            draft.value = Profile(
                id = profileId, householdRole = startingRole(savedState.get<String>(ARG_ROLE)), name = "", createdAt = now, modifiedAt = now,
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
            website = current.website.cleaned(),
            customDetails = CustomDetails.cleaned(current.customDetails),
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

    /** The user renamed a matter on the timeline; the status stays derived. */
    fun rename(caseId: String, title: String) {
        viewModelScope.launch { renameCase(caseId, title) }
    }

    // ── Contacts of an organisation: each change is one small use case ──

    /** Saves the edited details of a contact; what is typed is the contact's value from then on. */
    fun saveContact(contact: ContactPerson) = changeContact { updateContact(contact) }

    /** "No longer responsible" ([active] false) or responsible again. */
    fun setContactActive(contactId: String, active: Boolean) = changeContact { setContactActive.invoke(contactId, active) }

    /** [mergedId] is the same person as [keepId]: it goes into it, with its letters. */
    fun mergeContact(keepId: String, mergedId: String) = changeContact { mergeContacts(keepId, mergedId) }

    /** Moves the contact to another organisation profile. */
    fun moveContactTo(contactId: String, organisationId: String) = changeContact { moveContact(contactId, organisationId) }

    /** Deletes the contact; the letters stay. */
    fun removeContact(contactId: String) = changeContact { deleteContact(contactId) }

    /** The contact marked "to check" is right: the letters' contact fields that name it are confirmed, which clears the mark. */
    fun confirmContact(contactId: String) = changeContact { confirmContact.invoke(contactId) }

    /** The user typed a new contact into this organisation; it is theirs, never "suggested". */
    fun addContact(contact: ContactPerson) {
        viewModelScope.launch {
            val result = addContact.invoke(
                profileId, contact.name, contact.title, contact.department, contact.phone, contact.email, contact.customDetails,
            )
            if (result is PamResult.Error) _message.value = result.error.userMessage
        }
    }

    /** Discards a suggested contact: gone, with the tombstone, so reading its letter again does not bring it back. */
    fun discardContact(contactId: String) = changeContact { discardContact.invoke(contactId) }

    // ── What the organisation's letters showed, offered for its empty fields ──

    /**
     * Accepts suggestion [id] (as it is, or as the user [edited] it): the stored profile gets the value and so does the draft, so the
     * fields on the page show it at once and a later Save does not undo it.
     */
    fun acceptSuggestion(id: String, edited: String?) {
        val suggestion = uiState.value.suggestions.firstOrNull { it.suggestion.id == id }?.suggestion ?: return
        viewModelScope.launch {
            when (val result = acceptSuggestion.invoke(id, edited)) {
                is PamResult.Success -> {
                    val value = (edited ?: suggestion.value).trim()
                    draft.update { it?.let { d -> SuggestionRules.apply(d, suggestion.field, value, d.modifiedAt) } }
                    refreshStored()
                }
                is PamResult.Error -> _message.value = result.error.userMessage
            }
        }
    }

    /** "Dismiss": the value stays dismissed, so reading the letter again does not offer it again. */
    fun dismissSuggestion(id: String) {
        viewModelScope.launch {
            val result = dismissSuggestion.invoke(id)
            if (result is PamResult.Error) _message.value = result.error.userMessage
        }
    }

    /** "Accept all": the oldest suggestion of each field that is still empty on the page. */
    fun acceptAllSuggestions() {
        val current = draft.value ?: return
        val pending = uiState.value.suggestions.map { it.suggestion }
        val chosen = SuggestionRules.oldestPerField(SuggestionRules.open(current, pending))
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            when (val result = acceptSuggestion.acceptAll(profileId, chosen)) {
                is PamResult.Success -> {
                    draft.update { it?.let { d -> chosen.fold(d) { acc, s -> SuggestionRules.apply(acc, s.field, s.value, d.modifiedAt) } } }
                    refreshStored()
                }
                is PamResult.Error -> _message.value = result.error.userMessage
            }
        }
    }

    /** Re-reads the stored profile, so the next Save compares its household role with what is really stored. */
    private suspend fun refreshStored() {
        (profiles.getProfileById(profileId) as? PamResult.Success)?.let { stored = it.data }
    }

    private fun changeContact(change: suspend () -> PamResult<Unit>) {
        viewModelScope.launch {
            val result = change()
            if (result is PamResult.Error) _message.value = result.error.userMessage
        }
    }

    /** Called just before the dialer or the mail app is launched from this screen, so coming back does not trigger the app lock. */
    fun onExternalLaunching(reason: String) {
        externalFlowGuard.finish(externalFlow)
        externalFlow = externalFlowGuard.expect(reason)
    }

    /** The launch failed or its result came back: the protection is no longer needed. */
    fun onExternalLaunchFinished() {
        externalFlowGuard.finish(externalFlow)
        externalFlow = null
    }

    override fun onCleared() {
        externalFlowGuard.finish(externalFlow)
    }

    private var externalFlow: ExternalFlowToken? = null

    fun consumeMessage() {
        _message.value = null
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val ARG_PROFILE_ID = "profileId"

        /** Optional: the contact to scroll to on an organisation page (set when arriving from a letter's contact chip). */
        const val ARG_CONTACT_ID = "contactId"

        /** Optional: the matter to open expanded on the timeline (set when arriving from a letter's "Part of" row). */
        const val ARG_CASE_ID = "caseId"

        /** Optional, for a new person: the household role the editor opens with (`SELF` from the household card); a member when absent. */
        const val ARG_ROLE = "role"

        /** The household role a new person starts with: [requested] (a [HouseholdRole] name) when it names one, else a member. */
        internal fun startingRole(requested: String?): HouseholdRole =
            requested?.let { name -> HouseholdRole.entries.firstOrNull { it.name == name } } ?: HouseholdRole.MEMBER

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
    /** The contact the page was opened for: scrolled into view. */
    val focusContactId: String? = null,
    /** The other organisation profiles a contact can be moved to. */
    val otherOrganisations: List<Profile> = emptyList(),
    /** The timeline of a stored profile: matters first, then the plain events. */
    val timeline: TimelineUi = TimelineUi.EMPTY,
    /** The matter the page was opened for: starts expanded. */
    val focusCaseId: String? = null,
    /** What the organisation's letters showed for its fields, each with its letter's title; waiting for the user. */
    val suggestions: List<SuggestionView> = emptyList(),
) {
    val canSave: Boolean get() = draft?.name?.isNotBlank() == true
}
