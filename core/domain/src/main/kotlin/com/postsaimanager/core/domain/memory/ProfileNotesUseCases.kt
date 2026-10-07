package com.postsaimanager.core.domain.memory

import com.postsaimanager.core.domain.repository.DocumentNoteRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.NoteSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The notes of one household person as the context builder reads them: formatted ([DocumentMemoryFormat]) and capped, and current.
 * An empty string means there is nothing to remember about the person.
 */
class ObserveProfileMemoryUseCase @Inject constructor(
    private val notes: DocumentNoteRepository,
) {
    operator fun invoke(profileId: String): Flow<String> =
        notes.observeForProfile(profileId).map { DocumentMemoryFormat.format(it) }.distinctUntilChanged()
}

/**
 * The memory slot of the all-documents chat: the notes of the persons the conversation may be about, which for now are all household
 * persons (pinned notes first, then the most recent), and the household-wide notes. Each person's note is shown with the person's name
 * ("- Maria: ..."), so the model knows whom it concerns. Formatted and capped like every memory slot ([DocumentMemoryFormat]).
 *
 * A note about someone who is no longer a household person is not read (the profile may have stopped being managed).
 */
class ObserveHouseholdMemoryUseCase @Inject constructor(
    private val notes: DocumentNoteRepository,
    private val profiles: ProfileRepository,
) {
    operator fun invoke(): Flow<String> =
        combine(notes.observeOutsideDocuments(), profiles.getProfiles()) { all, everyone ->
            val household = everyone.filter { it.isManaged }.associate { it.id to it.name }
            val readable = all.filter { it.profileId == null || it.profileId in household }
            DocumentMemoryFormat.format(readable) { note -> note.profileId?.let(household::get) }
        }.distinctUntilChanged()
}

/** The notes of a household person as the user sees them in "What the assistant remembers", pinned first, then the newest. */
class ObserveProfileNotesUseCase @Inject constructor(
    private val notes: DocumentNoteRepository,
) {
    operator fun invoke(profileId: String): Flow<List<DocumentNote>> = notes.observeForProfile(profileId)
}

/** "Add a note" on a person: a note the user typed. Returns false (nothing written) when the text is empty. */
class AddProfileNoteUseCase @Inject constructor(
    private val notes: DocumentNoteRepository,
) {
    suspend operator fun invoke(profileId: String, text: String): Boolean {
        val clean = DocumentNoteText.clean(text) ?: return false
        notes.addOutsideDocument(profileId, clean, NoteSource.USER)
        return true
    }
}
