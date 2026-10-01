package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.LegacyTypes
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The documents the all-documents chat may talk about: not trashed, and not sensitive (a health letter: a sensitive
 * family such as `medical`, a sensitive topic such as `health`, or the legacy `health` type).
 *
 * This is the one owner of that rule. The chat's title list ([BuildChatContextUseCase]) and its
 * chunk corpus ([RetrieveChunksUseCase]) both read it, so a health letter can neither be named nor
 * quoted outside its own document chat, where it stays fully available.
 *
 * Known limit: the rule keys on the type the model chose. A health letter the model misclassifies
 * (as `info_no_action`, say) would leak into the all-documents chat. The stronger fix is the P2
 * per-person "sensitive" flag, which the user sets rather than the model guessing.
 */
class ObserveChatVisibleDocumentsUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
) {

    operator fun invoke(): Flow<List<Document>> =
        documentRepository.getDocuments().map { documents -> documents.filter(::isChatVisible) }

    /** The current visible documents, once. */
    suspend fun current(): List<Document> = invoke().first()

    companion object {
        private val SCHEMA = ExtractionSchema.DEFAULT

        fun isChatVisible(document: Document): Boolean = !document.isTrashed && !isSensitive(document)

        /**
         * Sensitive by its family or topics, or because the legacy type it was stored under ([LegacyTypes]) stands for a
         * sensitive family or topic, so a document read before extraction-v2-2 stays hidden until it is re-read.
         */
        private fun isSensitive(document: Document): Boolean {
            val type = document.extractionType
            if (SCHEMA.isSensitive(type, document.topics)) return true
            val legacy = LegacyTypes.of(type) ?: return false
            return SCHEMA.isSensitive(legacy.family, legacy.topics)
        }
    }
}
