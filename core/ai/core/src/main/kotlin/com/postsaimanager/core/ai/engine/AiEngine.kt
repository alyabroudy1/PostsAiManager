package com.postsaimanager.core.ai.engine

import com.postsaimanager.core.common.result.PamResult
import kotlinx.coroutines.flow.Flow

/**
 * Core AI engine interface.
 * Local (llama.cpp/Llamatik) and Remote (Claude/Gemini) engines
 * both implement this interface for a unified API.
 */
interface AiEngine {
    val engineType: EngineType
    val isReady: Boolean

    suspend fun initialize(modelPath: String, config: InferenceConfig): PamResult<Unit>
    fun generateStream(prompt: String, images: List<ByteArray> = emptyList()): Flow<String>
    suspend fun generate(prompt: String, images: List<ByteArray> = emptyList()): PamResult<String>
    suspend fun release()
}

enum class EngineType { LOCAL, REMOTE }

data class InferenceConfig(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val maxTokens: Int = 2048,
    val useGpu: Boolean = true,
    val contextWindow: Int = 4096,
)
