package com.postsaimanager.core.ai.catalog

import android.content.Context
import com.postsaimanager.core.model.InstalledModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Persisted shape of the installed-model index. */
@Serializable
data class InstalledIndex(
    val models: List<InstalledModel> = emptyList(),
    val activeModelId: String? = null,
    /**
     * Model used to read documents, when it differs from the chat model.
     *
     * Null means "the same one" — the common case, and the reason this is nullable rather
     * than defaulted to a copy of [activeModelId]. Storing a copy would silently freeze the
     * extraction choice the first time a user changed their chat model.
     */
    val extractionModelId: String? = null,
) {
    /**
     * Resolution rules, kept here rather than in the store so they can be tested without an
     * Android `Context`. They are decisions about data, and every one of them is a fallback
     * that decides whether a document gets read at all.
     */
    fun chatModel(): InstalledModel? = models.firstOrNull { it.id == activeModelId }

    /**
     * The model that reads documents.
     *
     * Falls back to the chat model when none is chosen, and again when the chosen one has
     * been uninstalled. Reading a document with a different model than the user picked is a
     * far better outcome than not reading it at all.
     */
    fun readerModel(): InstalledModel? =
        models.firstOrNull { it.id == extractionModelId } ?: chatModel()

    /** True when one model does both jobs — the default, and one load instead of two. */
    val sharesOneModel: Boolean
        get() = extractionModelId == null || extractionModelId == activeModelId
}

/**
 * Tracks which models are installed, which one chats, and which one reads documents.
 *
 * ### Why a JSON file rather than a Room table
 *
 * The source of truth here is the set of `.gguf` files on disk, not a row. Room would add
 * a schema version to maintain for data that is really a cache of the filesystem, and a
 * migration to write every time this index gains a field — [extractionModelId] would have
 * been one.
 *
 * It is also the more honest model: the source of truth is the set of `.gguf` files that
 * actually exist. [reconcile] drops index entries whose file has vanished — after a
 * "clear storage", a manual delete, or an interrupted install — so the store cannot claim a
 * model the engine would then fail to load.
 */
@Singleton
class InstalledModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    private val indexFile: File
        get() = File(modelsDir, INDEX_FILE_NAME)

    private val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    private val _state = MutableStateFlow(InstalledIndex())

    val installed: StateFlow<InstalledIndex>
        get() = _state.asStateFlow()

    init {
        load()
    }

    fun models(): List<InstalledModel> = _state.value.models

    fun activeModel(): InstalledModel? = _state.value.chatModel()

    /** See [InstalledIndex.readerModel]. */
    fun extractionModel(): InstalledModel? = _state.value.readerModel()

    fun isInstalled(descriptorId: String): Boolean =
        _state.value.models.any { it.descriptorId == descriptorId }

    fun add(model: InstalledModel) {
        update { index ->
            index.copy(
                models = index.models.filterNot { it.id == model.id } + model,
                // First install becomes active automatically — otherwise the user installs
                // a model and the assistant still reports that none is available.
                activeModelId = index.activeModelId ?: model.id,
            )
        }
    }

    fun remove(modelId: String) {
        update { index ->
            val remaining = index.models.filterNot { it.id == modelId }
            index.models.firstOrNull { it.id == modelId }?.let { File(it.filePath).delete() }
            index.copy(
                models = remaining,
                extractionModelId = index.extractionModelId?.takeIf { it != modelId },
                activeModelId = if (index.activeModelId == modelId) {
                    remaining.firstOrNull()?.id
                } else {
                    index.activeModelId
                },
            )
        }
    }

    fun setActive(modelId: String) {
        if (_state.value.models.none { it.id == modelId }) return
        update { it.copy(activeModelId = modelId) }
    }

    /** @param modelId null returns reading to whichever model chats. */
    fun setExtractionModel(modelId: String?) {
        if (modelId != null && _state.value.models.none { it.id == modelId }) return
        update { it.copy(extractionModelId = modelId) }
    }

    /** Drops entries whose backing file no longer exists. */
    fun reconcile() {
        update { index ->
            val present = index.models.filter { File(it.filePath).exists() }
            index.copy(
                models = present,
                activeModelId = index.activeModelId?.takeIf { id -> present.any { it.id == id } }
                    ?: present.firstOrNull()?.id,
                // Cleared rather than repointed: null already means "use the chat model",
                // which is the right answer when the chosen reader has gone.
                extractionModelId = index.extractionModelId
                    ?.takeIf { id -> present.any { it.id == id } },
            )
        }
    }

    private fun load() {
        val loaded = runCatching {
            if (indexFile.exists()) json.decodeFromString<InstalledIndex>(indexFile.readText())
            else InstalledIndex()
        }.getOrElse {
            // A corrupt index must not brick model management. The files on disk are the
            // real state; an empty index simply loses the "which is active" preference.
            InstalledIndex()
        }
        _state.value = loaded
        reconcile()
    }

    private fun update(block: (InstalledIndex) -> InstalledIndex) {
        val next = block(_state.value)
        _state.value = next
        runCatching { indexFile.writeText(json.encodeToString(InstalledIndex.serializer(), next)) }
    }

    private companion object {
        const val MODELS_DIR = "models"
        const val INDEX_FILE_NAME = "installed.json"
    }
}
