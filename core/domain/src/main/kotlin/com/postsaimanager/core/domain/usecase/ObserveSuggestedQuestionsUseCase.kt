package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.LegacyTypes
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The starter questions a chat offers before the first message.
 *
 * They are not written here: the model wrote three for each document while it read it, in the
 * letter's language, and they are stored on the document. This only chooses whose to show.
 *
 * - **A document's chat** shows that document's questions, and picks them up the moment extraction
 *   stores them (a chat opened right after scanning has none yet).
 * - **The all-documents chat** has no fixed list to fall back on. It shows the questions of the most
 *   recent document whose type asks something of its reader ([com.postsaimanager.core.domain.extraction.v2.DocFamily.actionable]),
 *   or nothing. Health letters are not actionable in that sense, so their questions never surface
 *   outside their own chat.
 */
class ObserveSuggestedQuestionsUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
) {

    fun forDocument(documentId: String): Flow<List<String>> =
        documentRepository.observeDocument(documentId)
            .map { document -> document?.suggestedQuestions.orEmpty().filter { it.isNotBlank() }.take(MAX_QUESTIONS) }
            .distinctUntilChanged()

    fun forAllDocuments(): Flow<List<String>> =
        documentRepository.getDocuments()
            .map { documents -> pick(documents) }
            .distinctUntilChanged()

    companion object {
        private const val MAX_QUESTIONS = 3

        private val SCHEMA = ExtractionSchema.V2

        /**
         * The questions of the newest document that has some, that the all-documents chat may show
         * ([ObserveChatVisibleDocumentsUseCase.isChatVisible], the one privacy rule) and whose family is actionable
         * (a legacy type id counts as the family it stands for, [LegacyTypes]); empty when none.
         */
        fun pick(documents: List<Document>): List<String> =
            documents
                .filter(ObserveChatVisibleDocumentsUseCase::isChatVisible)
                .sortedByDescending { it.createdAt }
                .firstOrNull { doc -> isActionable(doc.extractionType) && doc.suggestedQuestions.clean().isNotEmpty() }
                ?.suggestedQuestions?.clean()
                .orEmpty()

        private fun isActionable(typeId: String?): Boolean {
            val family = SCHEMA.family(typeId) ?: LegacyTypes.of(typeId)?.let { SCHEMA.family(it.family) }
            return family?.actionable == true
        }

        private fun List<String>.clean(): List<String> =
            map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_QUESTIONS)
    }
}
