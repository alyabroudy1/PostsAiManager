package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * What a chat model can be given in a message: the Gallery's `llmSupportImage` / `llmSupportAudio` as catalogue data
 * ([AiModelDescriptor.inputs]). Audio can be declared before the app uses it.
 */
@Serializable
enum class ModelInput { TEXT, IMAGE, AUDIO }
