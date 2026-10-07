/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Modified by PostsAiManager: the way the picture is read is the Google AI Edge Gallery's (common/Utils.kt decodeSampledBitmapFromUri,
// rotateBitmap and calculateInSampleSize; ui/common/chat/MessageInputText.kt, where a picked image is decoded to at most 1024 px,
// turned upright by its EXIF orientation and kept as PNG, the format their helper sends). Their Context-bound free functions
// become an injected store that writes into the app's private chat-attachments folder.

package com.postsaimanager.core.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.domain.ai.ChatImageStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** The numbers of scaling a picture down; pure, so they are tested without Android. */
object ChatImageSizing {

    /** The Gallery's bound for a picked image (`decodeSampledBitmapFromUri(context, uri, 1024, 1024)`). */
    const val MAX_SIDE = 1024

    /** `BitmapFactory`'s `inSampleSize` for a [width] x [height] picture to fit [maxSide]: the Gallery's `calculateInSampleSize`. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        if (width <= maxSide && height <= maxSide) return 1
        val heightRatio = (height.toFloat() / maxSide).roundToInt()
        val widthRatio = (width.toFloat() / maxSide).roundToInt()
        return max(max(heightRatio, widthRatio), 1)
    }
}

/**
 * The [ChatImageStore] on the app's private storage: `filesDir/chat-attachments/<conversation>/<id>.png`. A picture is scaled to
 * at most [ChatImageSizing.MAX_SIDE] pixels on a side and turned upright, as the Gallery does before it hands a picture to the model.
 */
@Singleton
class FileChatImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : ChatImageStore {

    private val root: File get() = File(context.filesDir, FOLDER)

    override suspend fun import(conversationId: String, source: String): String? = withContext(ioDispatcher) {
        runCatching {
            val uri = Uri.parse(source)
            val orientation = context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val decoded = decode(uri) ?: return@runCatching null
            val upright = rotate(decoded, orientation)
            val folder = File(root, safeName(conversationId)).apply { mkdirs() }
            val file = File(folder, "${UUID.randomUUID()}.png")
            file.outputStream().use { upright.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.absolutePath
        }.onFailure { Log.w(TAG, "could not store the picture: ${it.message}") }.getOrNull()
    }

    override suspend fun deleteAll(conversationId: String) {
        withContext(ioDispatcher) {
            runCatching { File(root, safeName(conversationId)).deleteRecursively() }
                .onFailure { Log.w(TAG, "could not delete the pictures: ${it.message}") }
        }
    }

    private fun decode(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = ChatImageSizing.sampleSize(bounds.outWidth, bounds.outHeight) }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1.0f, 1.0f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1.0f, -1.0f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1.0f, 1.0f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1.0f, 1.0f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** A conversation id as a folder name: nothing in it can leave [root]. */
    private fun safeName(conversationId: String): String = conversationId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val TAG = "FileChatImageStore"
        const val FOLDER = "chat-attachments"
    }
}
