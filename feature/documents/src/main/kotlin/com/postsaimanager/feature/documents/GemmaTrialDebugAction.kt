package com.postsaimanager.feature.documents

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.document.ReadAgainWithGemmaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Runs the debug action "Read again with Gemma (trial)" ([ReadAgainWithGemmaUseCase]); a debug build offers it and nothing else uses it. */
@HiltViewModel
class GemmaTrialDebugViewModel @Inject constructor(
    private val readAgainWithGemma: ReadAgainWithGemmaUseCase,
) : ViewModel() {

    fun readAgain(documentId: String) {
        viewModelScope.launch { readAgainWithGemma(documentId) }
    }
}

/**
 * The "Read again with Gemma (trial)" action of the document [documentId], or null in a release build (nothing is offered there). The
 * check is the app's own debuggable flag, as the chat's debug menu uses.
 */
@Composable
internal fun rememberReadAgainWithGemma(documentId: String): (() -> Unit)? {
    val context = LocalContext.current
    val debuggable = remember(context) { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    if (!debuggable) return null
    val viewModel = hiltViewModel<GemmaTrialDebugViewModel>()
    return remember(documentId) { { viewModel.readAgain(documentId) } }
}
