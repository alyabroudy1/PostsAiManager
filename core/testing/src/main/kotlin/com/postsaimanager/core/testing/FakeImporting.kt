package com.postsaimanager.core.testing

import com.postsaimanager.core.domain.importing.ImportQueue
import com.postsaimanager.core.domain.importing.ImportRequest
import com.postsaimanager.core.domain.importing.ImportRequestJournal
import com.postsaimanager.core.domain.importing.ImportResult
import com.postsaimanager.core.domain.importing.ImportStatus
import com.postsaimanager.core.domain.importing.ImportedKind
import com.postsaimanager.core.domain.importing.PageImageSource
import com.postsaimanager.core.domain.importing.RenderResult
import com.postsaimanager.core.domain.importing.StageResult
import com.postsaimanager.core.domain.importing.StagedFile
import kotlinx.coroutines.flow.MutableStateFlow

/** A staged file for tests: a PDF of [pages] pages or an image, hashed by [sha] (default: its id). */
fun stagedFile(
    id: String,
    kind: ImportedKind = ImportedKind.IMAGE,
    pages: Int = 1,
    sha: String = "sha-$id",
    passwordRequired: Boolean = false,
) = StagedFile(
    id = id, path = "/staged/$id", kind = kind, displayName = "$id.${if (kind == ImportedKind.PDF) "pdf" else "jpg"}",
    sizeBytes = 1_000, sha256 = sha, pageCount = if (passwordRequired) 0 else pages, passwordRequired = passwordRequired,
)

/**
 * In-memory [PageImageSource]. [stageResults] answers by URI (an unknown URI is staged as a one-page image named after it);
 * [renderResult] answers a render; everything the code under test asks is recorded.
 */
class FakePageImageSource : PageImageSource {

    override var supportsPasswordPdfs: Boolean = true

    val stageResults = mutableMapOf<String, StageResult>()
    var unlockResult: (StagedFile, String) -> StageResult = { file, _ -> StageResult.Staged(file.copy(pageCount = 2, passwordRequired = false)) }
    var renderResult: (StagedFile, String?) -> RenderResult = { file, _ ->
        RenderResult.Rendered((1..file.pageCount.coerceAtLeast(1)).map { "file:///pages/${file.id}-$it.jpg" })
    }
    var thumbnailResult: (StagedFile) -> String? = { "file:///thumbs/${it.id}.jpg" }

    val staged = mutableListOf<String>()
    val rendered = mutableListOf<StagedFile>()
    val renderPasswords = mutableListOf<String?>()
    val deletedPages = mutableListOf<String>()
    val discarded = mutableListOf<String>()

    override suspend fun stage(batchId: String, uri: String): StageResult {
        staged += uri
        return stageResults[uri] ?: StageResult.Staged(stagedFile(id = uri.substringAfterLast('/')))
    }

    override suspend fun unlock(file: StagedFile, password: String): StageResult = unlockResult(file, password)

    override suspend fun thumbnail(batchId: String, file: StagedFile, password: String?): String? = thumbnailResult(file)

    override suspend fun renderPages(batchId: String, file: StagedFile, password: String?): RenderResult {
        rendered += file
        renderPasswords += password
        return renderResult(file, password)
    }

    override suspend fun deletePages(pagePaths: List<String>) {
        deletedPages += pagePaths
    }

    override suspend fun discard(batchId: String) {
        discarded += batchId
    }
}

/** In-memory [ImportRequestJournal]: every saved request, in order, and the latest one as it would be on disk. */
class FakeImportRequestJournal : ImportRequestJournal {

    val saved = mutableListOf<ImportRequest>()
    val latest: ImportRequest? get() = saved.lastOrNull()

    override fun save(request: ImportRequest) {
        saved += request
    }
}

/** In-memory [ImportQueue]: records what was submitted and answers [awaitResult] with [result]. */
class FakeImportQueue : ImportQueue {

    val submitted = mutableListOf<ImportRequest>()
    var result: ImportResult = ImportResult(emptyList(), failedGroups = 0)
    val statusFlow = MutableStateFlow(ImportStatus())
    var failuresDismissed = 0

    override suspend fun submit(request: ImportRequest) {
        submitted += request
    }

    /** When set, [awaitResult] waits for it: a job that is still running. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun awaitResult(batchId: String): ImportResult {
        gate?.await()
        return result
    }

    override val status = statusFlow

    override fun dismissFailures() {
        failuresDismissed++
    }
}
