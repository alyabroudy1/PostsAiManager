package com.postsaimanager.core.data.backup

import com.postsaimanager.core.domain.backup.BackupManifest
import com.postsaimanager.core.domain.backup.CloudBackupStore
import com.postsaimanager.core.domain.backup.CloudResult
import com.postsaimanager.core.domain.backup.RemoteBackup
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant

/**
 * The Drive v3 REST calls the backup needs, on the Ktor client the app already ships: a resumable-protocol upload streamed from the file
 * (one request carrying the whole body, so the memory used is one buffer), list, streamed download and delete, all in `spaces=appDataFolder`.
 * A small client instead of `google-api-services-drive`: that library is a large, old, reflection-heavy dependency (and needs
 * Apache/Guava pieces R8 has to keep) for four HTTP calls; the Authorization API already gives the token it would otherwise fetch.
 */
internal class DriveRestCloudBackupStore(
    private val tokens: DriveTokenSource,
    private val client: HttpClient = defaultClient(),
) : CloudBackupStore {

    override suspend fun upload(file: File, manifest: BackupManifest, onProgress: (Long, Long) -> Unit): CloudResult<RemoteBackup> =
        call { token ->
            val metadata = buildJsonObject {
                put("name", file.name)
                put("parents", buildJsonArray { add(JsonPrimitive(APP_DATA_FOLDER)) })
                put(
                    "appProperties",
                    buildJsonObject {
                        put(PROP_CREATED_AT, manifest.createdAt.toString())
                        put(PROP_DEVICE, manifest.deviceName)
                        put(PROP_SCHEMA, manifest.dbSchemaVersion.toString())
                        put(PROP_APP_VERSION, manifest.appVersionName)
                    },
                )
            }
            val start = client.post("$UPLOAD_URL/files") {
                parameter("uploadType", "resumable")
                parameter("fields", FILE_FIELDS)
                bearerAuth(token)
                header("X-Upload-Content-Type", ZIP_TYPE)
                header("X-Upload-Content-Length", file.length().toString())
                contentType(ContentType.Application.Json)
                setBody(metadata.toString())
            }
            if (!start.status.isSuccess()) return@call failure(start)
            val session = start.headers[HttpHeaders.Location] ?: return@call CloudResult.Failure("Google Drive gave no upload address.")
            val total = file.length()
            val sent = client.put(session) {
                bearerAuth(token)
                setBody(FileContent(file, total, onProgress))
            }
            if (!sent.status.isSuccess()) return@call failure(sent)
            CloudResult.Success(JSON.decodeFromString(DriveFile.serializer(), sent.bodyAsText()).toBackup())
        }

    override suspend fun list(): CloudResult<List<RemoteBackup>> = call { token ->
        val found = mutableListOf<RemoteBackup>()
        var pageToken: String? = null
        do {
            val response = client.get("$API_URL/files") {
                bearerAuth(token)
                parameter("spaces", APP_DATA_FOLDER)
                parameter("fields", "nextPageToken,files($FILE_FIELDS)")
                parameter("orderBy", "createdTime desc")
                parameter("pageSize", PAGE_SIZE)
                pageToken?.let { parameter("pageToken", it) }
            }
            if (!response.status.isSuccess()) return@call failure(response)
            val page = JSON.decodeFromString(DriveFileList.serializer(), response.bodyAsText())
            found += page.files.map { it.toBackup() }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        CloudResult.Success(found.toList())
    }

    override suspend fun download(backup: RemoteBackup, target: File, onProgress: (Long, Long) -> Unit): CloudResult<Unit> = call { token ->
        client.prepareGet("$API_URL/files/${backup.id}") {
            bearerAuth(token)
            parameter("alt", "media")
        }.execute { response ->
            if (!response.status.isSuccess()) return@execute failure(response)
            val total = response.contentLength() ?: backup.sizeBytes
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(BUFFER)
            var received = 0L
            FileOutputStream(target).use { out ->
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                    received += read
                    onProgress(received, total)
                }
            }
            if (total > 0 && received != total) CloudResult.Failure("The download was cut short.") else CloudResult.Success(Unit)
        }
    }

    override suspend fun delete(backup: RemoteBackup): CloudResult<Unit> = call { token ->
        val response = client.delete("$API_URL/files/${backup.id}") { bearerAuth(token) }
        // 404: already gone, which is what was asked for.
        if (response.status.isSuccess() || response.status == HttpStatusCode.NotFound) CloudResult.Success(Unit) else failure(response)
    }

    private suspend fun <T> call(block: suspend (token: String) -> CloudResult<T>): CloudResult<T> {
        val token = when (val t = tokens.accessToken()) {
            is DriveToken.Token -> t.value
            DriveToken.NeedsConsent -> return CloudResult.AuthRequired
            is DriveToken.Failed -> return CloudResult.Failure(t.message)
        }
        return try {
            block(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            CloudResult.Failure(e.message ?: "No connection to Google Drive.")
        } catch (e: Exception) {
            CloudResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 401 means the token was refused (consent withdrawn): Settings must connect again. Anything else is shown with Google's own reason. */
    private suspend fun failure(response: HttpResponse): CloudResult<Nothing> {
        if (response.status == HttpStatusCode.Unauthorized) return CloudResult.AuthRequired
        val reason = runCatching {
            JSON.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return CloudResult.Failure("Google Drive answered ${response.status.value}${reason?.let { ": $it" }.orEmpty()}")
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    /** A file body streamed in [BUFFER] pieces, with the length known up front (so no chunked encoding, and a real progress). */
    private class FileContent(
        private val file: File,
        private val total: Long,
        private val onProgress: (Long, Long) -> Unit,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long = total
        override val contentType: ContentType = ContentType.parse(ZIP_TYPE)

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val buffer = ByteArray(BUFFER)
            var sent = 0L
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    channel.writeFully(buffer, 0, read)
                    sent += read
                    onProgress(sent, total)
                }
            }
            channel.flush()
        }
    }

    @Serializable
    private data class DriveFile(
        val id: String,
        val name: String = "",
        val size: String? = null,
        val createdTime: String? = null,
        val appProperties: Map<String, String>? = null,
    ) {
        fun toBackup() = RemoteBackup(
            id = id,
            name = name,
            createdAt = appProperties?.get(PROP_CREATED_AT)?.toLongOrNull()
                ?: createdTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: 0L,
            sizeBytes = size?.toLongOrNull() ?: 0L,
            deviceName = appProperties?.get(PROP_DEVICE),
            dbSchemaVersion = appProperties?.get(PROP_SCHEMA)?.toIntOrNull(),
            appVersionName = appProperties?.get(PROP_APP_VERSION),
        )
    }

    @Serializable
    private data class DriveFileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

    companion object {
        private const val API_URL = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3"
        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val FILE_FIELDS = "id,name,size,createdTime,appProperties"
        private const val ZIP_TYPE = "application/zip"
        private const val PAGE_SIZE = 100
        private const val BUFFER = 64 * 1024
        private const val PROP_CREATED_AT = "createdAt"
        private const val PROP_DEVICE = "device"
        private const val PROP_SCHEMA = "schema"
        private const val PROP_APP_VERSION = "appVersion"
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun defaultClient() = HttpClient(Android) {
            install(HttpTimeout) {
                // A backup can be hundreds of MB over a slow line: no limit on the whole request, a limit on silence.
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
        }
    }
}
