package com.postsaimanager.core.model

/**
 * Which stage of the document pipeline is running.
 *
 * Mirrors documentation/07-document-pipeline.md §2. `CAPTURE` and `LINK` are included for
 * completeness with that table even though [DocumentProcessingPipeline][com.postsaimanager
 * .core.data.repository.DocumentProcessingPipeline] does not yet report progress for them —
 * capture happens before processing starts, and profile linking today runs silently as part
 * of the pipeline's "Link" step.
 */
enum class ProcessingStage { CAPTURE, READ, UNDERSTAND, LINK, INDEX }

/**
 * Structured progress of the document pipeline for one document.
 *
 * Deliberately carries no English sentence — only a stage, a progress fraction, and the
 * numbers a message needs ([Running.currentPage], [Running.totalPages], [Running.fieldCount]).
 * `:core:data` produced hard-coded strings here until task 7.15.2 ("OCR: Page 2/5",
 * "Analyzing document structure...") — the data layer has no business deciding what English
 * a user reads. The feature layer (ViewModel or Composable) turns this into a sentence. See
 * documentation/README.md "Guiding principles" and the fact `stringResource` is used zero
 * times in this project: localisation is a separate, deliberate, tracked task, not something
 * to bake into the data layer by accident.
 */
sealed interface ProcessingState {
    data object Idle : ProcessingState

    data class Running(
        val documentId: String,
        val stage: ProcessingStage,
        /** 0f..1f, of the whole pipeline — not just the current stage. */
        val progress: Float,
        /** Set only during [ProcessingStage.READ] — the page currently being read. */
        val currentPage: Int? = null,
        /** Set only during [ProcessingStage.READ]. */
        val totalPages: Int? = null,
        /** Set only once fields have been saved, during [ProcessingStage.UNDERSTAND]. */
        val fieldCount: Int? = null,
    ) : ProcessingState

    data class Completed(val documentId: String) : ProcessingState
    data class Failed(val documentId: String, val error: String) : ProcessingState
}
