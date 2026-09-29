package com.postsaimanager.core.ai.tools

import com.postsaimanager.core.common.result.PamResult
import kotlinx.coroutines.flow.Flow

/**
 * Core interface for Text-To-Speech execution within the AI module.
 * Abstractions allow swapping between Native Android TTS, Kokoro ONNX,
 * or Edge network APIs without breaking presentation logic.
 */
interface TtsEngine {
    
    /**
     * Synthesizes audio and immediately begins playback.
     */
    fun speak(text: String, language: String = "ar"): PamResult<Unit>

    /**
     * Synthesizes the audio entirely into a buffer offline.
     * Useful for downloading or caching Kokoro PCM raw bytes.
     */
    suspend fun synthesizeToBuffer(text: String, language: String = "ar"): PamResult<ByteArray>

    /**
     * Instantly halt playback and synthesis generation.
     */
    fun stop()

    /**
     * Release heavy C++ ONNX resources or OS bindings.
     */
    fun release()
}
