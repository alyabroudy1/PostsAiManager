package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.ChatImagePolicy
import com.postsaimanager.core.domain.ai.ModelUse
import com.postsaimanager.core.domain.ai.loadForUse
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelRuntime

/**
 * The checks every reader that asks the chat model makes before it generates: a chat model is installed and is a LiteRT-LM one, the
 * pictures it may take, and the model loaded for a reading (the one-model rule: loading it replaces whatever is resident). One owner,
 * shared by the JSON reader ([ChatEngineGemmaReader]) and the question-and-answer reader ([QuestionAnswerGemmaReader]).
 */
class ReadingModelGate(
    private val engine: ChatEngine,
    private val activeModel: ActiveModelProvider,
) {

    sealed interface Opened {
        /** The model is loaded: its [config] and the [images] it may be shown (none when it cannot take pictures). */
        class Ready(val config: InferenceConfig, val images: List<String>) : Opened

        class Unavailable(val reason: String) : Opened
    }

    suspend fun open(imagePaths: List<String>, imageOnly: Boolean): Opened {
        val path = activeModel.activeModelPath() ?: return Opened.Unavailable("no chat model is installed")
        val config = activeModel.readingModelConfig()
        if (config.runtime != ModelRuntime.LITERT_LM) return Opened.Unavailable("the chat model is not a LiteRT-LM model")
        val images = if (ChatImagePolicy.enabledFor(config)) imagePaths else emptyList()
        if (imageOnly && images.isEmpty()) return Opened.Unavailable("no text and no picture the model can take")
        if (engine.loadForUse(ModelUse.READING, path, config) is PamResult.Error) return Opened.Unavailable("the chat model could not be loaded")
        return Opened.Ready(config, images)
    }
}
