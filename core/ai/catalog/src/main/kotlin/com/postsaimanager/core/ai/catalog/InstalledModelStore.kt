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
)

/**
 * Tracks which models are installed, and which one is active.
 *
 * ### Why a JSON file rather than a Room table
 *
 * Adding an entity means bumping the schema version, and `DatabaseModule` still uses
 * `fallbackToDestructiveMigration()` — every schema change wipes **all user documents**
 * (task 11.1). Storing this outside Room avoids forcing that trade now.
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

    fun activeModel(): InstalledModel? =
        _state.value.let { index -> index.models.firstOrNull { it.id == index.activeModelId } }

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

    /** Drops entries whose backing file no longer exists. */
    fun reconcile() {
        update { index ->
            val present = index.models.filter { File(it.filePath).exists() }
            index.copy(
                models = present,
                activeModelId = index.activeModelId?.takeIf { id -> present.any { it.id == id } }
                    ?: present.firstOrNull()?.id,
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
