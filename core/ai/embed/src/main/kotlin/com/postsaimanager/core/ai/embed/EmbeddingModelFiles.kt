package com.postsaimanager.core.ai.embed

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the embedding model lives on disk.
 *
 * One place that knows the layout, so the loader, the downloader and any diagnostics agree
 * rather than each spelling the paths out.
 *
 * Inside `filesDir` — private app storage — because the whole point of on-device embedding
 * is that document text never leaves the device, and a model in shared storage invites
 * other apps to swap it.
 */
@Singleton
class EmbeddingModelFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val directory: File get() = File(context.filesDir, RELATIVE_DIRECTORY)

    val modelFile: File get() = File(directory, MODEL_FILE_NAME)

    val vocabFile: File get() = File(directory, VOCAB_FILE_NAME)

    /**
     * Recorded against every vector produced, and checked at retrieval time. If this string
     * changes, previously stored vectors stop being used rather than being compared across
     * two incompatible spaces — so change it when, and only when, the model changes.
     */
    val modelId: String get() = MODEL_ID

    /**
     * Whether this model was trained on lowercased text. `false` here: German
     * capitalisation is meaning-bearing, and this checkpoint is a *cased* one.
     */
    val doLowerCase: Boolean get() = false

    fun arePresent(): Boolean = modelFile.isFile && vocabFile.isFile

    private companion object {
        const val RELATIVE_DIRECTORY = "models/embedding"
        const val MODEL_FILE_NAME = "model.onnx"
        const val VOCAB_FILE_NAME = "vocab.txt"
        const val MODEL_ID = "distiluse-base-multilingual-cased-v2"
    }
}
