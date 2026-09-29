package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.extraction.v2.DocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ModelDocumentInterpreter
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.OcrBlock
import javax.inject.Inject

/**
 * Reads a document and returns what was understood. A thin orchestrator: it loads the extraction
 * model when there is one and hands the pages to [ExtractionV2Pipeline]; the stages themselves
 * (layout, candidates, the one model call, verification, adaptation) live in
 * `core.domain.extraction.v2`, each behind its own port.
 *
 * ### The principle: the AI decides, code verifies
 *
 * Code finds value-shaped things and checks them. The model, in one grammar-constrained call, decides
 * the document type, what every value means, who is the sender, the addressee, the co-addressee, the
 * routing person and the subject person, and writes the free text. It can only answer with candidate
 * ids, enums and quotes that code then finds in the OCR text; a failed check lowers the confidence and
 * marks the value for review, and never replaces the model's answer with a rule's.
 *
 * ### Without a model
 *
 * No model installed, a model that fails to load, or an answer that cannot be read: the result is the
 * values code found, with no role and no meaning, marked "found" and low confidence
 * ([DocumentUnderstanding.modelUsed] is false). Nothing is guessed. This replaces the old split of
 * "regex extractor when the model fails".
 */
class AiExtractionUseCase @Inject constructor(
    private val engine: AiEngine,
    private val activeModelProvider: ActiveModelProvider,
) {

    private val pipeline = ExtractionV2Pipeline()
    private val adapter = ExtractionV2Adapter()

    /**
     * @param contextTokens overrides the window to budget against. Normally left null so it
     *   comes from the same source the model is loaded with; a budget computed against a
     *   different number than the one allocated is how a prompt silently overflows.
     * @param pageBlockCounts how many of [blocks] belong to each page, in page order (e.g.
     *   `[12, 8, 15]`, exactly how `DocumentProcessingPipeline` builds `blocks`). Left empty when
     *   page boundaries are not known, in which case the blocks are read as one page and a
     *   truncation is recorded without a page estimate.
     */
    suspend operator fun invoke(
        blocks: List<OcrBlock>,
        contextTokens: Int? = null,
        pageBlockCounts: List<Int> = emptyList(),
    ): PamResult<DocumentUnderstanding> {
        if (blocks.isEmpty()) return PamResult.Success(DocumentUnderstanding())

        val baseConfig = activeModelProvider.extractionModelConfig()
        val config = contextTokens?.let { baseConfig.copy(contextTokens = it) } ?: baseConfig
        val window = config.contextTokens

        val interpreter = loadedInterpreter(config)

        val result = pipeline.run(pages(blocks, pageBlockCounts), interpreter, window)
        val understanding = adapter.adapt(result)
        val truncation = understanding.inputTruncation
        return PamResult.Success(
            if (truncation != null && pageBlockCounts.isEmpty()) {
                understanding.copy(inputTruncation = truncation.copy(pagesRead = null, totalPages = null))
            } else {
                understanding
            },
        )
    }

    /**
     * The interpreter over the engine, or null when there is no model to run: none installed, or it
     * would not load. Reconciled on every call rather than only when the engine is not ready: the
     * engine may be ready on the chat model, or on this model with a stale configuration, and `load`
     * is cheap when nothing changed.
     */
    private suspend fun loadedInterpreter(config: InferenceConfig): DocumentInterpreter? {
        val path = activeModelProvider.extractionModelPath() ?: return null
        if (engine.load(path, config) is PamResult.Error) return null
        return ModelDocumentInterpreter(engine, contextTokens = config.contextTokens)
    }

    private fun pages(blocks: List<OcrBlock>, counts: List<Int>): List<List<OcrBlock>> {
        if (counts.isEmpty() || counts.sum() != blocks.size) return listOf(blocks)
        var from = 0
        return counts.map { n -> blocks.subList(from, from + n).also { from += n } }
    }
}
