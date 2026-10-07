package com.postsaimanager.core.domain.importing

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/**
 * A document that was already imported from the same file(s): the day it was added, and, when it sits in Recently deleted, the day it
 * was deleted ([deletedOn] is null for a document the person can see in the list).
 */
data class ImportedDuplicate(val documentId: String, val addedOn: LocalDate, val deletedOn: LocalDate? = null) {
    val isTrashed: Boolean get() = deletedOn != null
}

/**
 * Notices a file that was already added: the SHA-256 of its bytes is compared with what earlier imports stored. Byte-identical files
 * only; this decides nothing about meaning.
 *
 * What it answers must be something the person can act on. A live document is reported as it is. One in Recently deleted is reported
 * too, with the day it was deleted, so the sheet can offer to restore it rather than silently making a second copy. A row that has no
 * pages is not a document anyone can read (an import that was cut short), so it does not count and cannot block the file.
 */
class FindImportedDuplicateUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val clock: Clock,
) {

    suspend operator fun invoke(group: ImportGroup): ImportedDuplicate? {
        val existing = documents.findBySourceHash(group.sourceHash) ?: return null
        val pages = (documents.getDocumentPages(existing.id) as? PamResult.Success)?.data.orEmpty()
        if (pages.isEmpty()) return null
        return ImportedDuplicate(existing.id, dayOf(existing.createdAt), existing.deletedAt?.let(::dayOf))
    }

    private fun dayOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(clock.zone).toLocalDate()
}
