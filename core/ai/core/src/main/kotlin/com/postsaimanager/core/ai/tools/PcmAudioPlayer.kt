package com.postsaimanager.core.ai.tools

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult

/**
 * Utility class to stream raw uncompressed PCM ByteArrays generated
 * natively by the Kokoro vocoder directly to the Android Audio system.
 */
class PcmAudioPlayer(private val sampleRate: Int = 24000) {

    private var audioTrack: AudioTrack? = null
    
    @Volatile
    private var isPlaying = false

    /**
     * Instantiates the C++ native audio link.
     */
    private fun initAudioTrack(): PamResult<Unit> {
        return try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // We use STREAM_MUSIC to ensure volume maps to media keys natively
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            PamResult.Success(Unit)
        } catch (e: Exception) {
            PamResult.Error(PamError.Unknown("Failed to initialize Android AudioTrack.", e))
        }
    }

    /**
     * Plays a complete buffered byte array of PCM Audio.
     * Blocks until writing is complete (run this inside a Coroutine!).
     */
    fun playPcmData(pcmBytes: ByteArray): PamResult<Unit> {
        stop()
        isPlaying = true
        
        if (audioTrack == null) {
            val initRes = initAudioTrack()
            if (initRes is PamResult.Error) return initRes
        }
        
        return try {
            audioTrack?.play()
            // Write chunks to avoid blocking/stalling hardware buffer boundaries
            val chunkSize = 4096
            var offset = 0
            while (offset < pcmBytes.size && isPlaying) {
                val size = minOf(chunkSize, pcmBytes.size - offset)
                audioTrack?.write(pcmBytes, offset, size)
                offset += size
            }
            PamResult.Success(Unit)
        } catch (e: Exception) {
            PamResult.Error(PamError.Unknown("Error streaming PCM Audio to hardware.", e))
        } finally {
            if (isPlaying) {
                audioTrack?.stop()
                isPlaying = false
            }
        }
    }

    /**
     * Immediately halts hardware streaming.
     */
    fun stop() {
        isPlaying = false
        try {
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.pause()
                audioTrack?.flush()
            }
        } catch (e: Exception) {
            // Ignore interruption failures
        }
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
    }
}
