package com.postsaimanager.core.ai.local

import android.os.Parcel
import android.os.Parcelable
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.SamplingConfig

/**
 * Carries [InferenceConfig] across the AIDL boundary to the `:inference` process.
 *
 * Hand-written rather than `@Parcelize` — this module has no other reason to pull in the
 * kotlin-parcelize plugin for one class. Every field of [InferenceConfig] is flattened onto
 * this one Parcelable, including [SamplingConfig], rather than nesting a second Parcelable,
 * since AIDL's `in`/`out` marshalling is simplest with one flat type per boundary crossing.
 */
class InferenceConfigParcel(
    val contextTokens: Int,
    val batchTokens: Int,
    val threads: Int,
    val threadsBatch: Int,
    val useMmap: Boolean,
    val useMlock: Boolean,
    val flashAttention: Boolean,
    val accelerator: String,
    val gpuLayers: Int,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    /** -1 means "no seed requested" — see [SamplingConfig.seed]. */
    val seed: Long,
) : Parcelable {

    fun toInferenceConfig(): InferenceConfig = InferenceConfig(
        contextTokens = contextTokens,
        batchTokens = batchTokens,
        threads = threads,
        threadsBatch = threadsBatch,
        useMmap = useMmap,
        useMlock = useMlock,
        flashAttention = flashAttention,
        accelerator = Accelerator.fromLabel(accelerator),
        gpuLayers = gpuLayers,
        sampling = SamplingConfig(
            temperature = temperature,
            topK = topK,
            topP = topP,
            seed = seed.takeIf { it >= 0 },
        ),
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(contextTokens)
        dest.writeInt(batchTokens)
        dest.writeInt(threads)
        dest.writeInt(threadsBatch)
        dest.writeInt(if (useMmap) 1 else 0)
        dest.writeInt(if (useMlock) 1 else 0)
        dest.writeInt(if (flashAttention) 1 else 0)
        dest.writeString(accelerator)
        dest.writeInt(gpuLayers)
        dest.writeFloat(temperature)
        dest.writeInt(topK)
        dest.writeFloat(topP)
        dest.writeLong(seed)
    }

    companion object CREATOR : Parcelable.Creator<InferenceConfigParcel> {
        override fun createFromParcel(parcel: Parcel): InferenceConfigParcel = InferenceConfigParcel(
            contextTokens = parcel.readInt(),
            batchTokens = parcel.readInt(),
            threads = parcel.readInt(),
            threadsBatch = parcel.readInt(),
            useMmap = parcel.readInt() != 0,
            useMlock = parcel.readInt() != 0,
            flashAttention = parcel.readInt() != 0,
            accelerator = parcel.readString() ?: "CPU",
            gpuLayers = parcel.readInt(),
            temperature = parcel.readFloat(),
            topK = parcel.readInt(),
            topP = parcel.readFloat(),
            seed = parcel.readLong(),
        )

        override fun newArray(size: Int): Array<InferenceConfigParcel?> = arrayOfNulls(size)

        fun from(config: InferenceConfig): InferenceConfigParcel = InferenceConfigParcel(
            contextTokens = config.contextTokens,
            batchTokens = config.batchTokens,
            threads = config.threads,
            threadsBatch = config.threadsBatch,
            useMmap = config.useMmap,
            useMlock = config.useMlock,
            flashAttention = config.flashAttention,
            accelerator = config.accelerator.name,
            gpuLayers = config.gpuLayers,
            temperature = config.sampling.temperature,
            topK = config.sampling.topK,
            topP = config.sampling.topP,
            seed = config.sampling.seed ?: -1L,
        )
    }
}
