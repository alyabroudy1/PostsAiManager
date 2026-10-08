package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.model.InferenceConfig
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * "This run reads documents with the model at [path]": a coroutine-context element a reading run carries, so that the engine can
 * check, whenever the run opens a prompt session, that the model resident is the reader and not whatever else was loaded in
 * between (the chat model, replaced mid-document). The engine loads the reader again when it was replaced; it never scores on
 * another model.
 *
 * It is an element of the caller's context rather than a parameter of [PromptSession.open] because other prompt-session users
 * (form filling) legitimately run on other models, and because the reading pipeline opens sessions deep inside code that has no
 * business knowing which model file it runs on.
 *
 * @param path the reader model's file, as [ActiveModelProvider.extractionModelPath] gave it to [AiEngine.load].
 * @param config the config the reader was loaded with, used when it has to be loaded again.
 */
class ReadingModel(val path: String, val config: InferenceConfig) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReadingModel>
}
