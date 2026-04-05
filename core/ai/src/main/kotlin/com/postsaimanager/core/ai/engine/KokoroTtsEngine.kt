package com.postsaimanager.core.ai.engine

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import com.postsaimanager.core.ai.tools.TtsEngine
import com.postsaimanager.core.ai.tools.PcmAudioPlayer
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * The sophisticated Kokoro-82M ONNX offline execution engine.
 * Maps exact tensor shapes based on hexgrad community port.
 */
class KokoroTtsEngine(private val context: Context) : TtsEngine {

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    
    // Voice profile contains hundreds of blends. We use the first 256 frame-blend vector.
    private val styleBuffer = FloatArray(256)
    
    // Uses 24000Hz (standard Kokoro pitch)
    private val audioPlayer = PcmAudioPlayer(24000)

    val isReady: Boolean
        get() = session != null

    init {
        // Auto-mount assets to native cache on DI Initialization
        try {
            val modelOut = copyAssetToCache("kokoro_82m.onnx")
            val voiceOut = copyAssetToCache("voice_af_bella.bin")
            
            loadVoiceProfile(voiceOut)
            initializeSession(modelOut.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun copyAssetToCache(assetName: String): File {
        val outFile = File(context.cacheDir, assetName)
        if (!outFile.exists() || outFile.length() == 0L) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile
    }

    private fun loadVoiceProfile(file: File) {
        val bytes = file.readBytes()
        // Kokoro style bin files are native float32 chunks mapping [511, 256].
        // We unpack the very first row [256].
        val floatBuf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for (i in 0 until 256) {
            if (floatBuf.hasRemaining()) {
                styleBuffer[i] = floatBuf.get()
            }
        }
    }

    private fun initializeSession(modelPath: String) {
        ortEnv = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        session = ortEnv?.createSession(modelPath, sessionOptions)
    }

    override fun speak(text: String, language: String): PamResult<Unit> {
        if (!isReady) return PamResult.Error(PamError.ModelNotLoaded("Kokoro"))

        return try {
            // Because synthesization blocks natively, wrap it in a thread.
            // When using actual Kotlin coroutines, this should be launched gracefully.
            val audioData = runBlockingSynthesize(text)
            
            if (audioData is PamResult.Success) {
                audioPlayer.playPcmData(audioData.data)
                PamResult.Success(Unit)
            } else {
                PamResult.Error((audioData as PamResult.Error).error)
            }
        } catch (e: Exception) {
            PamResult.Error(PamError.InferenceError("Speak pipeline dashed.", e))
        }
    }

    private fun runBlockingSynthesize(text: String): PamResult<ByteArray> {
        return kotlinx.coroutines.runBlocking(Dispatchers.Default) {
            synthesizeToBuffer(text, "en-us")
        }
    }

    override suspend fun synthesizeToBuffer(text: String, language: String): PamResult<ByteArray> {
        return withContext(Dispatchers.Default) {
            if (!isReady) return@withContext PamResult.Error(PamError.ModelNotLoaded("Kokoro"))
            val sess = session ?: return@withContext PamResult.Error(PamError.ModelNotLoaded("KokoroSession"))
            val env = ortEnv ?: return@withContext PamResult.Error(PamError.Unknown("Env Lost"))

            try {
                // STEP 1: Text Tokenization (Basic character mapping)
                // Maps basic English text directly to Kokoro's Vocab JSON indices.
                // For 'h' (104 ascii) -> 50 in Kokoro space, 'a' -> 43, ' ' -> 16
                val L = text.length.toLong().coerceAtLeast(1)
                val simulatedTokens = LongArray(L.toInt()) { i -> 
                    val char = text.getOrNull(i)?.lowercaseChar() ?: ' '
                    when {
                        char in 'a'..'z' -> (char - 'a' + 43).toLong()
                        char == ' ' -> 16L
                        char == '.' -> 4L
                        char == '!' -> 5L
                        char == '?' -> 6L
                        char == ',' -> 3L
                        else -> 0L // padding/unknown
                    }
                }

                val tokenShape = longArrayOf(1, L)
                val tokenBuffer = LongBuffer.wrap(simulatedTokens)
                val tokenTensor = OnnxTensor.createTensor(env, tokenBuffer, tokenShape)

                // STEP 2: Style Profile Loading & Matrix Slicing
                // voice_af_bella.bin contains 512 rows, each containing 256 floats!
                // The ONNX model simply expects a single [1, 256] style vector.
                // Kokoro slices the dynamic row based on the number of generated tokens.
                val styleBytes = File(context.cacheDir, "voice_af_bella.bin").readBytes()
                val styleFloats = FloatArray(styleBytes.size / 4)
                ByteBuffer.wrap(styleBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(styleFloats)
                
                val maxRows = styleFloats.size / 256
                val targetRow = L.toInt().coerceAtMost(maxRows - 1)
                val startIdx = targetRow * 256
                val endIdx = startIdx + 256
                val slicedStyleFloats = styleFloats.copyOfRange(startIdx, endIdx)

                val styleShape = longArrayOf(1, 256)
                val styleBuffer = FloatBuffer.wrap(slicedStyleFloats)
                val styleTensor = OnnxTensor.createTensor(env, styleBuffer, styleShape)

                // STEP 3: Speed scalar
                val speedShape = longArrayOf(1)
                val speedBuffer = FloatBuffer.wrap(floatArrayOf(1.0f)) // 1.0x speed
                val speedTensor = OnnxTensor.createTensor(env, speedBuffer, speedShape)

                // STEP 4: Fused Acoustic + Vocoder Execution
                val inputs = mapOf(
                    "input_ids" to tokenTensor,
                    "style" to styleTensor,
                    "speed" to speedTensor
                )
                
                val output = sess.run(inputs)
                
                // Audio comes out as a single float stream
                val targetOutputData = output.get("waveform").get().value as? FloatArray
                    ?: (output.get("waveform").get().value as? Array<FloatArray>)?.get(0)
                    ?: return@withContext PamResult.Error(PamError.InferenceError("Invalid PCM shape from Kokoro output layer."))

                tokenTensor.close()
                styleTensor.close()
                speedTensor.close()
                output.close()
                
                // STEP 5: Float -> 16bit PCM Encoding
                val outputBytes = floatArrayTo16BitPcm(targetOutputData)
                
                PamResult.Success(outputBytes)
            } catch (e: Exception) {
                PamResult.Error(PamError.InferenceError("Kokoro Synthesis failed: ${e.message}", e))
            }
        }
    }

    private fun floatArrayTo16BitPcm(floatArray: FloatArray): ByteArray {
        val byteArray = ByteArray(floatArray.size * 2)
        for (i in floatArray.indices) {
            val f = floatArray[i].coerceIn(-1.0f, 1.0f)
            val shortVal = (f * 32767.0f).toInt().toShort()
            byteArray[i * 2] = (shortVal.toInt() and 0x00FF).toByte()
            byteArray[i * 2 + 1] = ((shortVal.toInt() and 0xFF00) shr 8).toByte()
        }
        return byteArray
    }

    override fun stop() {
        audioPlayer.stop()
    }

    override fun release() {
        stop()
        audioPlayer.release()
        session?.close()
        session = null
    }
}
