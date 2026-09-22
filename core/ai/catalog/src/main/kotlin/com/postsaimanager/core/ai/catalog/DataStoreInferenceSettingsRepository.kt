package com.postsaimanager.core.ai.catalog

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.domain.repository.InferenceSettingsRepository
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceOverrides
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.inferenceSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "inference_settings")

private object InferenceSettingsKeys {
    val THREADS = intPreferencesKey("threads")
    val CONTEXT_TOKENS = intPreferencesKey("context_tokens")
    val ACCELERATOR = stringPreferencesKey("accelerator")
    val TEMPERATURE = floatPreferencesKey("temperature")
    val TOP_K = intPreferencesKey("top_k")
    val TOP_P = floatPreferencesKey("top_p")
    val FLASH_ATTENTION = booleanPreferencesKey("flash_attention")
    val GPU_BLOCKED_MODELS = stringSetPreferencesKey("gpu_blocked_models")
}

/**
 * Preferences DataStore-backed [InferenceSettingsRepository], mirroring
 * `UserPreferencesRepositoryImpl` in `:core:data` field for field: one preference key per
 * override, a null/missing key meaning "no opinion", and reads that fall back to
 * [InferenceOverrides.NONE] on a corrupt store rather than propagating the failure — a
 * broken settings file must not stop the model from loading with its defaults.
 */
@Singleton
class DataStoreInferenceSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : InferenceSettingsRepository {

    override val overrides: Flow<InferenceOverrides> = context.inferenceSettingsDataStore.data
        .map { prefs ->
            InferenceOverrides(
                threads = prefs[InferenceSettingsKeys.THREADS],
                contextTokens = prefs[InferenceSettingsKeys.CONTEXT_TOKENS],
                accelerator = prefs[InferenceSettingsKeys.ACCELERATOR]?.let(Accelerator::fromLabel),
                temperature = prefs[InferenceSettingsKeys.TEMPERATURE],
                topK = prefs[InferenceSettingsKeys.TOP_K],
                topP = prefs[InferenceSettingsKeys.TOP_P],
                flashAttention = prefs[InferenceSettingsKeys.FLASH_ATTENTION],
            )
        }
        .catch { emit(InferenceOverrides.NONE) }
        .flowOn(ioDispatcher)

    override suspend fun update(overrides: InferenceOverrides) = withContext(ioDispatcher) {
        context.inferenceSettingsDataStore.edit { prefs ->
            overrides.threads.applyTo(prefs, InferenceSettingsKeys.THREADS)
            overrides.contextTokens.applyTo(prefs, InferenceSettingsKeys.CONTEXT_TOKENS)
            overrides.accelerator?.name.applyTo(prefs, InferenceSettingsKeys.ACCELERATOR)
            overrides.temperature.applyTo(prefs, InferenceSettingsKeys.TEMPERATURE)
            overrides.topK.applyTo(prefs, InferenceSettingsKeys.TOP_K)
            overrides.topP.applyTo(prefs, InferenceSettingsKeys.TOP_P)
            overrides.flashAttention.applyTo(prefs, InferenceSettingsKeys.FLASH_ATTENTION)
        }
        Unit
    }

    override suspend fun reset() = withContext(ioDispatcher) {
        context.inferenceSettingsDataStore.edit { it.clear() }
        Unit
    }

    // Deliberately survives reset(): a crash-derived block is a fact about the device and
    // this model's compatibility with it, not a user preference — resetInference() (the
    // "restore defaults" affordance) clearing it would offer GPU straight back onto the
    // exact config that just aborted the inference process.
    override val gpuBlockedModels: Flow<Set<String>> = context.inferenceSettingsDataStore.data
        .map { prefs -> prefs[InferenceSettingsKeys.GPU_BLOCKED_MODELS] ?: emptySet() }
        .catch { emit(emptySet()) }
        .flowOn(ioDispatcher)

    override suspend fun blockGpu(modelId: String) = withContext(ioDispatcher) {
        context.inferenceSettingsDataStore.edit { prefs ->
            val current = prefs[InferenceSettingsKeys.GPU_BLOCKED_MODELS] ?: emptySet()
            prefs[InferenceSettingsKeys.GPU_BLOCKED_MODELS] = current + modelId
        }
        Unit
    }

    override suspend fun unblockGpu(modelId: String) = withContext(ioDispatcher) {
        context.inferenceSettingsDataStore.edit { prefs ->
            val current = prefs[InferenceSettingsKeys.GPU_BLOCKED_MODELS] ?: emptySet()
            prefs[InferenceSettingsKeys.GPU_BLOCKED_MODELS] = current - modelId
        }
        Unit
    }

    /** Null clears the key (falls back to default) rather than writing a sentinel. */
    private fun <T : Any> T?.applyTo(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: androidx.datastore.preferences.core.Preferences.Key<T>,
    ) {
        if (this != null) prefs[key] = this else prefs.remove(key)
    }
}
