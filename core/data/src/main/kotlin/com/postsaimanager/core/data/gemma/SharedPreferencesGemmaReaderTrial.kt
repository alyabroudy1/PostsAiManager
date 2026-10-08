package com.postsaimanager.core.data.gemma

import android.content.Context
import com.postsaimanager.core.domain.extraction.gemma.GemmaReaderTrial
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The trial's switch on the app's private preferences (`gemma_reader_trial`), off until a debug build's Settings turns it on, and the
 * one-off requests of the "Read again with Gemma (trial)" action, held in memory (a request only has to outlive the few seconds
 * between the tap and the reading it asked for).
 */
@Singleton
class SharedPreferencesGemmaReaderTrial @Inject constructor(
    @ApplicationContext private val context: Context,
) : GemmaReaderTrial {

    private val preferences get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(readSwitch())

    override val enabled: Flow<Boolean> = state.asStateFlow()

    private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun isEnabled(): Boolean = state.value

    override suspend fun setEnabled(enabled: Boolean) {
        runCatching { preferences.edit().putBoolean(KEY_ENABLED, enabled).apply() }
        state.value = enabled
    }

    override fun requestOnce(documentId: String) {
        requested += documentId
    }

    override fun takeRequest(documentId: String): Boolean = requested.remove(documentId)

    private fun readSwitch(): Boolean = runCatching { preferences.getBoolean(KEY_ENABLED, false) }.getOrDefault(false)

    private companion object {
        const val FILE = "gemma_reader_trial"
        const val KEY_ENABLED = "enabled"
    }
}
