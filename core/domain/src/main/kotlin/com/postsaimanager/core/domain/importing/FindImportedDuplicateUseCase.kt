package com.postsaimanager.core.domain.importing

import com.postsaimanager.core.domain.repository.DocumentRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** A document that was already imported from the same file(s), and the day it was added. */
data class ImportedDuplicate(val documentId: String, val addedOn: LocalDate)

/**
 * Notices a file that was already added: the SHA-256 of its bytes is compared with what earlier imports stored. Byte-identical files
 * only; this decides nothing about meaning. A trashed document does not count.
 */
class FindImportedDuplicateUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val clock: Clock,
) {

    suspend operator fun invoke(group: ImportGroup): ImportedDuplicate? =
        documents.findBySourceHash(group.sourceHash)?.let { existing ->
            ImportedDuplicate(existing.id, Instant.ofEpochMilli(existing.createdAt).atZone(clock.zone).toLocalDate())
        }
}
