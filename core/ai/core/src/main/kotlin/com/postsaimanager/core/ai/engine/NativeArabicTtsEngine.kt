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
 * TTS engine backed by Android's built-in [TextToSpeech].
 *
 * Handles every language the device has a voice pack for, Arabic included, and is
 * used for document read-aloud.
 *
 * Previously this delegated non-Arabic text to a bundled Kokoro-82M ONNX model.
 * That path was removed 2026-08-07: the 86 MB model was fed raw ASCII instead of
 * IPA phonemes, so it produced noise rather than speech. Android's own engine is
 * both correct and free. See documentation/02-architecture.md §9.
 */
class NativeArabicTtsEngine(private val context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var hasArabicVoice = false

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

                tts?.setSpeechRate(0.9f)
                tts?.setPitch(1.0f)

                Log.i("NativeArabicTTS", "Initialized. Arabic voice available: $hasArabicVoice")
            } else {
                Log.e("NativeArabicTTS", "TTS initialization failed with status: $status")
            }
        }
    }

    private fun isArabicText(text: String): Boolean {
        return text.any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F }
    }

    override fun speak(text: String, language: String): PamResult<Unit> {
        if (!isInitialized) {
            return PamResult.Error(PamError.ModelNotLoaded("Android TTS not initialized yet."))
        }

        if (isArabicText(text) && !hasArabicVoice) {
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

            if (isArabicText(text) && !hasArabicVoice) {
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
    }

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
