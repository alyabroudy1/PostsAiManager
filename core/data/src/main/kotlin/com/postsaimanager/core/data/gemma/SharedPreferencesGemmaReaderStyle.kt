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
 * The "Reader style: JSON / Questions" switch on the app's private preferences (`gemma_reader_style`): Questions until a debug build's
 * Settings chooses JSON (the fallback). A stored value that is not a style reads as the default, Questions.
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

    private val alwaysState = MutableStateFlow(runCatching { preferences.getBoolean(KEY_ALWAYS_IMAGE, false) }.getOrDefault(false))

    override val alwaysImage: Flow<Boolean> = alwaysState.asStateFlow()

    override suspend fun alwaysSendImage(): Boolean = alwaysState.value

    override suspend fun setAlwaysImage(always: Boolean) {
        runCatching { preferences.edit().putBoolean(KEY_ALWAYS_IMAGE, always).apply() }
        alwaysState.value = always
    }

    private fun read(): ReaderStyle =
        runCatching { preferences.getString(KEY, null) }.getOrNull()?.let { name -> ReaderStyle.entries.firstOrNull { it.name == name } } ?: ReaderStyle.QUESTIONS

    private companion object {
        const val FILE = "gemma_reader_style"
        const val KEY = "reader_style"
        const val KEY_ALWAYS_IMAGE = "questions_always_image"
    }
}
