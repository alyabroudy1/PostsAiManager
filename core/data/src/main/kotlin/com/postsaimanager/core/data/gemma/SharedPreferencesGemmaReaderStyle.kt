package com.postsaimanager.core.data.gemma

import android.content.Context
import com.postsaimanager.core.domain.extraction.gemma.GemmaReaderStyle
import com.postsaimanager.core.domain.extraction.gemma.ReaderStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Reader style: JSON / Questions" switch on the app's private preferences (`gemma_reader_style`): JSON until a debug build's
 * Settings chooses Questions. A stored value that is not a style reads as JSON.
 */
@Singleton
class SharedPreferencesGemmaReaderStyle @Inject constructor(
    @ApplicationContext private val context: Context,
) : GemmaReaderStyle {

    private val preferences get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())

    override val style: Flow<ReaderStyle> = state.asStateFlow()

    override suspend fun current(): ReaderStyle = state.value

    override suspend fun set(style: ReaderStyle) {
        runCatching { preferences.edit().putString(KEY, style.name).apply() }
        state.value = style
    }

    private fun read(): ReaderStyle =
        runCatching { preferences.getString(KEY, null) }.getOrNull()?.let { name -> ReaderStyle.entries.firstOrNull { it.name == name } } ?: ReaderStyle.JSON

    private companion object {
        const val FILE = "gemma_reader_style"
        const val KEY = "reader_style"
    }
}
