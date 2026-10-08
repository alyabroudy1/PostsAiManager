package com.postsaimanager.core.data.gemma

import android.content.Context
import com.postsaimanager.core.domain.ai.ReadingAcceleratorSetting
import com.postsaimanager.core.model.Accelerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Reading: CPU / GPU" setting on the app's private preferences (`reading_accelerator`): CPU until a debug build's Settings chooses
 * GPU. A stored value that is not an accelerator reads as CPU.
 */
@Singleton
class SharedPreferencesReadingAcceleratorSetting @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReadingAcceleratorSetting {

    private val preferences get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())

    override val accelerator: Flow<Accelerator> = state.asStateFlow()

    override suspend fun current(): Accelerator = state.value

    override suspend fun set(accelerator: Accelerator) {
        runCatching { preferences.edit().putString(KEY, accelerator.name).apply() }
        state.value = accelerator
    }

    private fun read(): Accelerator =
        runCatching { preferences.getString(KEY, null) }.getOrNull()?.let(Accelerator::fromLabel) ?: Accelerator.CPU

    private companion object {
        const val FILE = "reading_accelerator"
        const val KEY = "reading_accelerator"
    }
}
