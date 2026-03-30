package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * AI model metadata — represents a downloadable/downloaded LLM.
 */
@Serializable
data class AiModel(
    val id: String,
    val name: String,
    val provider: AiProvider,
    val modelType: AiModelType,
    val fileName: String? = null,
    val downloadUrl: String? = null,
    val sizeBytes: Long = 0,
    val quantization: String? = null,
    val parameterCount: String? = null,
    val description: String = "",
    val capabilities: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val contextWindow: Int = 4096,
    val minRamGb: Int = 4,
    val isDownloaded: Boolean = false,
    val localPath: String? = null,
    val downloadProgress: Float = 0f,
    val isDefault: Boolean = false,
    val lastUsedAt: Long? = null,
)

@Serializable
enum class AiProvider {
    LLAMA_CPP,
    CLAUDE,
    GEMINI,
}

@Serializable
enum class AiModelType {
    LOCAL,
    REMOTE,
}
