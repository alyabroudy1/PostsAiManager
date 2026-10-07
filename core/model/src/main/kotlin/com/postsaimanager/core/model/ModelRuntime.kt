package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * The inference runtime that runs a model file. Catalogue data: a descriptor names its runtime, and nothing else in the app
 * decides it, so a feature never branches on it (the chat engine router picks the engine from it).
 */
@Serializable
enum class ModelRuntime(
    /** The extension of the model file as it is stored on the phone. */
    val fileExtension: String,
) {
    /** llama.cpp, running GGUF files: reads documents (scoring, KV prefix reuse) and chats. */
    LLAMA_CPP("gguf"),

    /** LiteRT-LM, running `.litertlm` files (Google AI Edge Gallery's engine): chat only, GPU where the phone has one. */
    LITERT_LM("litertlm"),
    ;

    /** Whether this runtime can read documents (needs token scoring and prefix reuse, which only llama.cpp has). */
    val canReadDocuments: Boolean get() = this == LLAMA_CPP
}
