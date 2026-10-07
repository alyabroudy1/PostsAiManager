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
 *
 * The job may run twice when the process dies midway and WorkManager starts it again, so progress is written to the batch's request
 * through [journal]: a group's document id as soon as the document exists (that group is skipped on the second run), and a PDF's
 * password as soon as the PDF has been opened and rendered (it is not kept longer than it is needed).
 */
class ImportFilesUseCase @Inject constructor(
    private val pageImages: PageImageSource,
    private val createDocument: CreateDocumentFromPagesUseCase,
    private val journal: ImportRequestJournal,
) {

    /** The request as it is now: groups done and passwords used up are already taken out of it. */
    private class Run(var request: ImportRequest) {
        fun save(journal: ImportRequestJournal) {
            // Best effort: not being able to write the journal must not stop the import itself.
            runCatching { journal.save(request) }
        }
    }

    /** @param onProgress called with (documents finished, documents in all) after each group. */
    suspend operator fun invoke(request: ImportRequest, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportOutcome {
        val created = mutableListOf<String>()
        val problems = mutableListOf<ImportProblem>()
        val run = Run(request)
        try {
            request.groups.forEachIndexed { index, group ->
                val already = request.created[index]
                if (already != null) {
                    created += already
                } else {
                    when (val result = importGroup(run, group)) {
                        is GroupResult.Created -> {
                            created += result.documentId
                            run.request = run.request.copy(created = run.request.created + (index to result.documentId))
                            run.save(journal)
                        }
                        is GroupResult.Problem -> problems += result.problem
                    }
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

    private suspend fun importGroup(run: Run, group: ImportGroup): GroupResult {
        val batchId = run.request.batchId
        val pages = mutableListOf<String>()
        try {
            for (file in group.files) {
                val password = run.request.passwords[file.id]
                when (val rendered = pageImages.renderPages(batchId, file, password)) {
                    is RenderResult.Rendered -> {
                        pages += rendered.pagePaths
                        if (password != null) {
                            // The PDF is open and rendered: the password has done its job and goes from the disk at once.
                            run.request = run.request.copy(passwords = run.request.passwords - file.id)
                            run.save(journal)
                        }
                    }
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
