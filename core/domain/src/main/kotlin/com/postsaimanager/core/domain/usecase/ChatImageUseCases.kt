package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.ChatImagePolicy
import com.postsaimanager.core.domain.ai.ChatImageStore
import javax.inject.Inject

/** Copies a picture the user attached into the app's own storage, ready to be sent with a message. */
class AttachChatImageUseCase @Inject constructor(private val images: ChatImageStore) {

    /** The stored file of the picture at [source] (a URI string), or null when it is not a picture the app can read. */
    suspend operator fun invoke(conversationId: String, source: String): String? = images.import(conversationId, source)
}

/** Whether the model chat would use right now can look at an attached picture. */
class ChatImageSupportUseCase @Inject constructor(private val activeModel: ActiveModelProvider) {

    suspend operator fun invoke(): Boolean =
        runCatching { ChatImagePolicy.enabledFor(activeModel.activeModelConfig()) }.getOrDefault(false)
}
