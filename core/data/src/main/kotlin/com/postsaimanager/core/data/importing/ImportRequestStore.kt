package com.postsaimanager.core.data.importing

import android.content.Context
import com.postsaimanager.core.domain.importing.ImportRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a confirmed [ImportRequest] in its batch's private folder, so the background job needs only the batch id (WorkManager's input
 * data is small and persisted) and so a job restarted after the process died still knows what to do. The file can hold a PDF's
 * password; it is deleted with the batch.
 */
@Singleton
class ImportRequestStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(request: ImportRequest) {
        val file = file(request.batchId)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ImportRequest.serializer(), request))
    }

    /** The request of [batchId], or null when it is gone or unreadable. */
    fun load(batchId: String): ImportRequest? =
        runCatching { json.decodeFromString(ImportRequest.serializer(), file(batchId).readText()) }.getOrNull()

    private fun file(batchId: String): File = File(AndroidPageImageSource.batchDirectory(context, batchId), FILE_NAME)

    private companion object {
        const val FILE_NAME = "request.json"
    }
}
