package com.postsaimanager.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.extraction.gemma.GemmaReaderStyle
import com.postsaimanager.core.domain.extraction.gemma.ReaderStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The debug switch "Reader style: JSON / Questions": on, Gemma reads a letter by asking one labelled question as the chat does; off (the
 * default), it answers the constrained JSON schema. Settings shows it in a debug build only.
 */
@HiltViewModel
class GemmaReaderStyleViewModel @Inject constructor(
    private val setting: GemmaReaderStyle,
) : ViewModel() {

    val questions: StateFlow<Boolean> = setting.style
        .map { it == ReaderStyle.QUESTIONS }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun setQuestions(questions: Boolean) {
        viewModelScope.launch { setting.set(if (questions) ReaderStyle.QUESTIONS else ReaderStyle.JSON) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
