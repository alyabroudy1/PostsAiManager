package com.postsaimanager.core.domain.importing

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.usecase.CreateDocumentFromPagesUseCase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The conversion half of importing, run by the background job: for each confirmed group, render its files into page images and hand
 * them to [CreateDocumentFromPagesUseCase], the one place a set of pages becomes a document that reads itself. The pipeline after
 * that (OCR, extraction, people, actions, reminders, search) is exactly the scan path.
 *
 * Each document is all or nothing: a group that fails to render or to store leaves no pages behind, and the others still go on.
 * Whatever happens (success, failure, the job being cancelled) the batch's temporary copies are removed at the end.
 */
class ImportFilesUseCase @Inject constructor(
    private val pageImages: PageImageSource,
    private val createDocument: CreateDocumentFromPagesUseCase,
) {

    /** @param onProgress called with (documents finished, documents in all) after each group. */
    suspend operator fun invoke(request: ImportRequest, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportOutcome {
        val created = mutableListOf<String>()
        val problems = mutableListOf<ImportProblem>()
        try {
            request.groups.forEachIndexed { index, group ->
                when (val result = importGroup(request, group)) {
                    is GroupResult.Created -> created += result.documentId
                    is GroupResult.Problem -> problems += result.problem
                }
                onProgress(index + 1, request.groups.size)
            }
        } finally {
            // Also when the job is cancelled: the copies of a letter must not linger in the app's storage.
            withContext(NonCancellable) { pageImages.discard(request.batchId) }
        }
        return ImportOutcome(created, problems)
    }

    private sealed interface GroupResult {
        data class Created(val documentId: String) : GroupResult
        data class Problem(val problem: ImportProblem) : GroupResult
    }

    private suspend fun importGroup(request: ImportRequest, group: ImportGroup): GroupResult {
        val pages = mutableListOf<String>()
        try {
            for (file in group.files) {
                when (val rendered = pageImages.renderPages(request.batchId, file, request.passwords[file.id])) {
                    is RenderResult.Rendered -> pages += rendered.pagePaths
                    is RenderResult.Failed -> return GroupResult.Problem(rendered.problem)
                }
            }
            val original = group.files.singleOrNull()?.takeIf { it.kind == ImportedKind.PDF }?.let { "file://${it.path}" }
            return when (val result = createDocument(pages, group.sourceType, group.sourceHash, original)) {
                is PamResult.Success -> GroupResult.Created(result.data)
                is PamResult.Error -> GroupResult.Problem(ImportProblem.Unreadable(group.files.first().displayName))
            }
        } finally {
            // The repository copied the pages into the document; these are the batch's own, and are no longer needed.
            withContext(NonCancellable) { pageImages.deletePages(pages) }
        }
    }
}
