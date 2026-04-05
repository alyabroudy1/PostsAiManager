package com.postsaimanager.core.ai.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.postsaimanager.core.ai.tools.TtsEngine
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Arabic-aware TTS engine using Android's built-in TextToSpeech.
 *
 * The Kokoro-82M ONNX model is English-only and cannot process Arabic script.
 * This engine delegates to Android's native TTS which supports Arabic through
 * installed voice packs (Google TTS, Samsung TTS, etc.).
 *
 * When available, falls back to Kokoro for English content.
 */
class NativeArabicTtsEngine(private val context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null
    @Volatile
    private var isInitialized = false
    @Volatile
    private var hasArabicVoice = false

    // Kokoro engine for English fallback
    private var kokoroEngine: KokoroTtsEngine? = null

    init {
        initializeTts()
    }

    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true

                // Try to set Arabic locale
                val arabicLocale = Locale("ar")
                val result = tts?.setLanguage(arabicLocale)
                hasArabicVoice = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED

                if (!hasArabicVoice) {
                    // Try other Arabic locales
                    val arabicVariants = listOf(
                        Locale("ar", "SA"),  // Saudi Arabic
                        Locale("ar", "EG"),  // Egyptian Arabic
                        Locale("ar", "AE"),  // UAE Arabic
                    )
                    for (locale in arabicVariants) {
                        val r = tts?.setLanguage(locale)
                        if (r != TextToSpeech.LANG_MISSING_DATA &&
                            r != TextToSpeech.LANG_NOT_SUPPORTED) {
                            hasArabicVoice = true
                            break
                        }
                    }
                }

                // Tuning for Arabic clarity
                tts?.setSpeechRate(0.85f)    // Slightly slower for iʻrāb clarity
                tts?.setPitch(1.0f)

                Log.i("NativeArabicTTS", "Initialized. Arabic voice available: $hasArabicVoice")
            } else {
                Log.e("NativeArabicTTS", "TTS initialization failed with status: $status")
            }
        }

        // Try to initialize Kokoro for English fallback
        try {
            kokoroEngine = KokoroTtsEngine(context)
        } catch (e: Exception) {
            Log.w("NativeArabicTTS", "Kokoro fallback not available: ${e.message}")
        }
    }

    private fun isArabicText(text: String): Boolean {
        return text.any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F }
    }

    override fun speak(text: String, language: String): PamResult<Unit> {
        if (!isInitialized) {
            return PamResult.Error(PamError.ModelNotLoaded("Android TTS not initialized yet."))
        }

        // Route to Kokoro for English if available
        if (!isArabicText(text) && kokoroEngine?.isReady == true) {
            return kokoroEngine?.speak(text, language)
                ?: PamResult.Error(PamError.InferenceError("Kokoro unavailable"))
        }

        // Use Android TTS for Arabic
        if (!hasArabicVoice) {
            return PamResult.Error(
                PamError.InferenceError(
                    "No Arabic TTS voice installed. Please install Google TTS or Samsung TTS with Arabic language pack from Google Play."
                )
            )
        }

        return try {
            val utteranceId = UUID.randomUUID().toString()
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.SUCCESS) {
                PamResult.Success(Unit)
            } else {
                PamResult.Error(PamError.InferenceError("TTS speak() returned error code: $result"))
            }
        } catch (e: Exception) {
            PamResult.Error(PamError.InferenceError("TTS failed: ${e.message}", e))
        }
    }

    override suspend fun synthesizeToBuffer(text: String, language: String): PamResult<ByteArray> {
        return withContext(Dispatchers.IO) {
            if (!isInitialized) {
                return@withContext PamResult.Error(PamError.ModelNotLoaded("TTS not ready"))
            }

            // For non-Arabic, try Kokoro
            if (!isArabicText(text) && kokoroEngine?.isReady == true) {
                return@withContext kokoroEngine?.synthesizeToBuffer(text, language)
                    ?: PamResult.Error(PamError.InferenceError("Kokoro unavailable"))
            }

            if (!hasArabicVoice) {
                return@withContext PamResult.Error(
                    PamError.InferenceError("No Arabic TTS voice installed.")
                )
            }

            try {
                val utteranceId = UUID.randomUUID().toString()
                val outputFile = File(context.cacheDir, "tts_output_$utteranceId.wav")

                val result = suspendCancellableCoroutine<PamResult<ByteArray>> { continuation ->
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            if (id == utteranceId) {
                                try {
                                    val bytes = outputFile.readBytes()
                                    outputFile.delete()
                                    continuation.resume(PamResult.Success(bytes))
                                } catch (e: Exception) {
                                    continuation.resume(
                                        PamResult.Error(PamError.InferenceError("Failed to read TTS output", e))
                                    )
                                }
                            }
                        }
                        @Deprecated("Deprecated in API")
                        override fun onError(utteranceId: String?) {
                            continuation.resume(
                                PamResult.Error(PamError.InferenceError("TTS synthesis error"))
                            )
                        }
                    })

                    tts?.synthesizeToFile(text, null, outputFile, utteranceId)
                }
                result
            } catch (e: Exception) {
                PamResult.Error(PamError.InferenceError("TTS buffer synthesis failed: ${e.message}", e))
            }
        }
    }

    override fun stop() {
        tts?.stop()
        kokoroEngine?.stop()
    }

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
        kokoroEngine?.release()
        kokoroEngine = null
    }
}
