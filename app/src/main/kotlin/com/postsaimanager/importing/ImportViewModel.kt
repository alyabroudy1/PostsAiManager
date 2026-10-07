package com.postsaimanager.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.importing.FindImportedDuplicateUseCase
import com.postsaimanager.core.domain.importing.ImportGroup
import com.postsaimanager.core.domain.importing.ImportGrouping
import com.postsaimanager.core.domain.importing.ImportProblem
import com.postsaimanager.core.domain.importing.ImportQueue
import com.postsaimanager.core.domain.importing.ImportRequest
import com.postsaimanager.core.domain.importing.ImportedDuplicate
import com.postsaimanager.core.domain.importing.ImportedKind
import com.postsaimanager.core.domain.importing.PageImageSource
import com.postsaimanager.core.domain.importing.StageResult
import com.postsaimanager.core.domain.importing.StagedFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One document the sheet will create, with the duplicate warning and whether it is included. */
data class ImportRow(
    val group: ImportGroup,
    val duplicate: ImportedDuplicate?,
    /** Thumbnails (`file://` URIs) of the first page of the group's files, by file id; a file without one is absent. */
    val thumbnails: Map<String, String>,
    /** A duplicate is left out until the person chooses to add it again; anything else is in. */
    val included: Boolean,
)

/** Where the activity goes when the import is submitted and the job has finished. */
sealed interface ImportTarget {
    data class OpenDocument(val documentId: String) : ImportTarget
    data object OpenList : ImportTarget

    /** Nothing could be added. */
    data object Failed : ImportTarget

    /** Cancelled: nothing was imported. */
    data object Close : ImportTarget
}

enum class ImportStage { READING, REVIEW, ADDING }

data class ImportUiState(
    val stage: ImportStage = ImportStage.READING,
    /** The accepted files in the shared order, locked PDFs included. */
    val files: List<StagedFile> = emptyList(),
    val problems: List<ImportProblem> = emptyList(),
    val eachImageSeparate: Boolean = false,
    val rows: List<ImportRow> = emptyList(),
    /** Ids of the locked PDFs whose last password was wrong. */
    val wrongPasswords: Set<String> = emptySet(),
    /** The job is queued (so leaving the screen is safe). */
    val submitted: Boolean = false,
    val target: ImportTarget? = null,
) {
    /** Locked PDFs waiting for a password; they are not part of any document yet. */
    val lockedFiles: List<StagedFile> get() = files.filter { it.passwordRequired }

    /** The switch is offered only where it changes something: two or more images. */
    val showSeparateSwitch: Boolean get() = files.count { it.kind == ImportedKind.IMAGE } >= 2

    val canAdd: Boolean get() = stage == ImportStage.REVIEW && rows.any { it.included }
}

/**
 * The confirm sheet's brain: stages what was shared (after the app lock opened), groups it by the rules in [ImportGrouping],
 * notices files added before, asks the password of a locked PDF, and on confirmation hands the request to the background job.
 * Decides nothing about content; the document's meaning is the pipeline's, later.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val pageImages: PageImageSource,
    private val queue: ImportQueue,
    private val findDuplicate: FindImportedDuplicateUseCase,
) : ViewModel() {

    private val batchId = UuidGenerator.generate()
    private var started = false
    private val passwords = mutableMapOf<String, String>()
    private val thumbnails = mutableMapOf<String, String>()
    private val duplicates = mutableMapOf<String, ImportedDuplicate?>()
    private val addAgain = mutableSetOf<String>()

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    val passwordPdfsSupported: Boolean get() = pageImages.supportsPasswordPdfs

    /** Starts once, however often the screen is recreated; call it only after the app lock is open. */
    fun start(uris: List<String>) {
        if (started) return
        started = true
        viewModelScope.launch {
            val (readable, refused) = ImportSources.split(uris)
            val files = mutableListOf<StagedFile>()
            val problems = mutableListOf<ImportProblem>()
            refused.mapTo(problems) { ImportProblem.NotSupported(it.substringAfterLast('/').ifBlank { "file" }) }
            for (uri in readable) {
                when (val result = pageImages.stage(batchId, uri)) {
                    is StageResult.Staged -> files += result.file
                    is StageResult.Rejected -> problems += result.problem
                }
            }
            _state.update { it.copy(files = files, problems = problems) }
            refreshRows()
            _state.update { it.copy(stage = ImportStage.REVIEW) }
            files.filter { !it.passwordRequired }.forEach { loadThumbnail(it) }
        }
    }

    fun setEachImageSeparate(separate: Boolean) {
        _state.update { it.copy(eachImageSeparate = separate) }
        viewModelScope.launch { refreshRows() }
    }

    /** The person chose to add (or not to add) a file they added before. */
    fun setAddAgain(group: ImportGroup, add: Boolean) {
        if (add) addAgain += group.sourceHash else addAgain -= group.sourceHash
        viewModelScope.launch { refreshRows() }
    }

    fun unlock(fileId: String, password: String) {
        val file = _state.value.files.firstOrNull { it.id == fileId && it.passwordRequired } ?: return
        viewModelScope.launch {
            when (val result = pageImages.unlock(file, password)) {
                is StageResult.Staged -> {
                    passwords[fileId] = password
                    _state.update { s ->
                        s.copy(
                            files = s.files.map { if (it.id == fileId) result.file else it },
                            wrongPasswords = s.wrongPasswords - fileId,
                        )
                    }
                    refreshRows()
                    loadThumbnail(result.file)
                }
                is StageResult.Rejected -> when (result.problem) {
                    is ImportProblem.WrongPassword -> _state.update { it.copy(wrongPasswords = it.wrongPasswords + fileId) }
                    else -> {
                        // Too many pages, damaged: this file is out, the rest stays.
                        _state.update { s -> s.copy(files = s.files.filter { it.id != fileId }, problems = s.problems + result.problem) }
                        refreshRows()
                    }
                }
            }
        }
    }

    /** Cancel: nothing is imported and every temporary copy goes. */
    fun cancel() {
        discardBatch()
        _state.update { it.copy(target = ImportTarget.Close) }
    }

    /** "Add to my letters". */
    fun confirm() {
        val current = _state.value
        if (!current.canAdd) return
        val groups = current.rows.filter { it.included }.map { it.group }
        val request = ImportRequest(
            batchId = batchId,
            groups = groups,
            passwords = groups.flatMap { it.files }.mapNotNull { f -> passwords[f.id]?.let { f.id to it } }.toMap(),
        )
        _state.update { it.copy(stage = ImportStage.ADDING) }
        viewModelScope.launch {
            queue.submit(request)
            _state.update { it.copy(submitted = true) }
            val result = queue.awaitResult(batchId)
            val target = when {
                result.documentIds.size == 1 && result.failedGroups == 0 -> ImportTarget.OpenDocument(result.documentIds.single())
                result.documentIds.isNotEmpty() -> ImportTarget.OpenList
                else -> ImportTarget.Failed
            }
            _state.update { it.copy(target = target) }
        }
    }

    /** "Continue in the background": the job runs on; the list shows it. */
    fun hide() {
        if (_state.value.submitted) _state.update { it.copy(target = ImportTarget.OpenList) }
    }

    override fun onCleared() {
        // Left without confirming (swiped away, back pressed): the copies of someone's letters must not stay.
        if (!_state.value.submitted) discardBatch()
    }

    private fun discardBatch() {
        // Not in viewModelScope: it is cancelled when the model is cleared, and the cleanup must still run.
        cleanupScope.launch { withContext(NonCancellable) { pageImages.discard(batchId) } }
    }

    /** Outlives the model; a test replaces it with a scope it can wait on. */
    internal var cleanupScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    private suspend fun loadThumbnail(file: StagedFile) {
        val path = pageImages.thumbnail(batchId, file, passwords[file.id]) ?: return
        thumbnails[file.id] = path
        refreshRows()
    }

    private suspend fun refreshRows() {
        val state = _state.value
        val groups = ImportGrouping.group(state.files.filter { !it.passwordRequired }, state.eachImageSeparate)
        groups.forEach { group ->
            if (group.sourceHash !in duplicates) duplicates[group.sourceHash] = findDuplicate(group)
        }
        val rows = groups.map { group ->
            val duplicate = duplicates[group.sourceHash]
            ImportRow(
                group = group,
                duplicate = duplicate,
                thumbnails = group.files.mapNotNull { f -> thumbnails[f.id]?.let { f.id to it } }.toMap(),
                included = duplicate == null || group.sourceHash in addAgain,
            )
        }
        _state.update { it.copy(rows = rows) }
    }
}
