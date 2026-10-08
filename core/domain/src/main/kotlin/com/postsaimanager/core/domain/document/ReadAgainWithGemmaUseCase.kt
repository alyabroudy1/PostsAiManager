package com.postsaimanager.core.domain.document

import com.postsaimanager.core.domain.extraction.gemma.GemmaReaderTrial
import javax.inject.Inject

/**
 * The debug action "Read again with Gemma (trial)": the document's next reading is Gemma's, whatever the trial's switch says, and the
 * reading is queued like any "Read again". Reviewed rows survive it (the merge protects them), so only what nobody has looked at changes.
 * A debug build offers it; nothing else calls it.
 */
class ReadAgainWithGemmaUseCase @Inject constructor(
    private val trial: GemmaReaderTrial,
    private val processor: DocumentProcessor,
) {

    suspend operator fun invoke(documentId: String) {
        trial.requestOnce(documentId)
        processor.enqueue(documentId, force = true)
    }
}
