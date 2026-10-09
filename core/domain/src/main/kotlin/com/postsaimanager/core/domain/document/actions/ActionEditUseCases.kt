package com.postsaimanager.core.domain.document.actions

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.extraction.actions.ActionKinds
import com.postsaimanager.core.domain.extraction.actions.ActionPart
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ActionSource
import java.time.LocalDate
import javax.inject.Inject

/**
 * What a person gives an action: its kind (an id of [ActionKinds]), their own wording and their own due date.
 *
 * @property text the action in their words; blank: the sentence rendered from the kind
 * @property dueDate ISO `yyyy-MM-dd`, or blank/unreadable for "the date of the bound field"
 */
data class ActionEdit(val kind: String, val text: String? = null, val dueDate: String? = null) {

    /** The wording as stored (trimmed, null when empty). */
    internal fun cleanText(): String? = text?.trim()?.takeIf { it.isNotEmpty() }

    /** The due date as stored: an ISO date, null when empty or not a date. */
    internal fun cleanDueDate(): String? = dueDate?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() }
}

private fun invalid(message: String): PamResult<Unit> = PamResult.Error(PamError.ValidationError("action", message))

private suspend fun DocumentRepository.storedActions(documentId: String): List<ActionItem>? =
    (getDocumentById(documentId) as? PamResult.Success)?.data?.actionItems

/**
 * The user edits an action of a letter: the wording, the kind (a chooser of the kinds) and the due date. The action becomes theirs
 * ([ActionSource.USER]), so no re-read replaces it, and the model's action it came from is remembered ([ActionItem.origin]) so a reading
 * that chooses it again does not add it a second time ([ActionItemsPolicy]).
 */
class EditActionUseCase @Inject constructor(private val documents: DocumentRepository) {

    suspend operator fun invoke(documentId: String, original: ActionItem, edit: ActionEdit): PamResult<Unit> {
        val kind = ActionKinds.of(edit.kind) ?: return invalid("unknown action kind ${edit.kind}")
        val stored = documents.storedActions(documentId) ?: return invalid("no document $documentId")
        val index = stored.indexOf(original).takeIf { it >= 0 } ?: return invalid("the action is no longer there")
        val edited = original.copy(
            kind = kind.id,
            // A part the new kind does not state is no longer bound.
            bindings = original.bindings.filterKeys { key -> ActionPart.of(key)?.let { it in kind.parts } == true },
            source = ActionSource.USER,
            text = edit.cleanText(),
            dueDate = edit.cleanDueDate(),
            origin = original.origin ?: original.kind.takeIf { original.source == ActionSource.MODEL },
            removed = false,
        )
        return documents.setActionItems(documentId, stored.toMutableList().also { it[index] = edited })
    }
}

/**
 * The user deletes an action of a letter. A model action (or one the person edited from a model's) is replaced by a tombstone, so a
 * re-read that chooses it again does not bring it back; an action the person added is simply removed.
 */
class DeleteActionUseCase @Inject constructor(private val documents: DocumentRepository) {

    suspend operator fun invoke(documentId: String, original: ActionItem): PamResult<Unit> {
        val stored = documents.storedActions(documentId) ?: return invalid("no document $documentId")
        val index = stored.indexOf(original).takeIf { it >= 0 } ?: return invalid("the action is no longer there")
        val addedByUser = original.source == ActionSource.USER && original.origin == null
        val rest = stored.toMutableList()
        if (addedByUser) {
            rest.removeAt(index)
        } else {
            rest[index] = ActionItem(kind = original.kind, source = ActionSource.USER, origin = original.origin, removed = true)
        }
        return documents.setActionItems(documentId, rest)
    }
}

/** The user adds an action of their own to a letter (the kind from the chooser, their wording, an optional due date); it is theirs for good. */
class AddActionUseCase @Inject constructor(private val documents: DocumentRepository) {

    suspend operator fun invoke(documentId: String, edit: ActionEdit): PamResult<Unit> {
        val kind = ActionKinds.of(edit.kind) ?: return invalid("unknown action kind ${edit.kind}")
        if (edit.cleanText() == null && edit.cleanDueDate() == null && kind.id == ActionKinds.OTHER.id) return invalid("an action needs its wording")
        val stored = documents.storedActions(documentId) ?: return invalid("no document $documentId")
        val added = ActionItem(kind = kind.id, source = ActionSource.USER, text = edit.cleanText(), dueDate = edit.cleanDueDate())
        return documents.setActionItems(documentId, stored + added)
    }
}
