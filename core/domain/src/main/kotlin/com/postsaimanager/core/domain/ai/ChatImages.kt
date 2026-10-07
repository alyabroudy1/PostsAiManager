package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelRuntime

/**
 * When chat offers "attach an image", and whether a reply is given one. A pure decision, so no screen and no engine repeats it.
 * Image input is the Gallery's: only a LiteRT-LM model whose catalogue entry declares it looks at a picture; the llama.cpp chat
 * models have no vision here.
 */
object ChatImagePolicy {

    fun enabledFor(config: InferenceConfig): Boolean = config.runtime == ModelRuntime.LITERT_LM && config.supportsImages
}

/**
 * Where the pictures a user attaches to a chat message live. Each is copied, scaled down, into the app's own storage (the picker's
 * `content://` URI is not ours and may stop working), in the app's private `chat-attachments/<conversation>/` folder. Only the
 * paths travel to the engine (over AIDL): no picture bytes cross the binder.
 */
interface ChatImageStore {

    /**
     * Copies the picture at [source] (a `content://` or `file://` URI, as the picker or a stored page gives it), scaled to what
     * the model takes, and returns the file it now lives in, or null when it could not be read as a picture.
     */
    suspend fun import(conversationId: String, source: String): String?

    /** Deletes every picture of [conversationId]. Safe to call when there are none. */
    suspend fun deleteAll(conversationId: String)
}
