package com.postsaimanager.feature.parser

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.ai.engine.OnnxArabicSyntaxEngine
import com.postsaimanager.core.ai.tools.TtsEngine
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.ArabicSyntaxTree
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

sealed interface ParserUiState {
    data object Idle : ParserUiState
    data object Loading : ParserUiState
    data class Success(val tree: ArabicSyntaxTree) : ParserUiState
    data class Error(val message: String) : ParserUiState
}

@HiltViewModel
class ParserViewModel @Inject constructor(
    private val syntaxEngine: OnnxArabicSyntaxEngine,
    private val ttsEngine: TtsEngine,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<ParserUiState>(ParserUiState.Idle)
    val uiState: StateFlow<ParserUiState> = _uiState.asStateFlow()

    private var isEngineReady = false

    init {
        // Initialize the ONNX Runtime engine. We copy the asset to cache since ORT needs an absolute path.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val assetName = "arabic_syntax_hrm_v2.ort"
                val outFile = File(context.cacheDir, assetName)
                
                // Force copy on every boot to guarantee uncorrupted asset alignment
                if (outFile.exists()) {
                    outFile.delete()
                }
                
                context.assets.open(assetName).use { inputStream ->
                    java.io.FileOutputStream(outFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                val result = syntaxEngine.initialize(outFile.absolutePath)
                if (result is PamResult.Success) {
                    isEngineReady = true
                } else if (result is PamResult.Error) {
                    _uiState.value = ParserUiState.Error(result.error.userMessage)
                }
            } catch (e: Exception) {
                // If the model asset doesn't exist, we notify the user.
                _uiState.value = ParserUiState.Error("Failed to load ONNX model. Ensure 'arabic_syntax_hrm_v2.ort' is in the Android assets folder. Trace: ${e.message}")
            }
        }
    }

    fun parseArabicText(text: String) {
        if (text.isBlank()) return
        if (!isEngineReady) {
            _uiState.value = ParserUiState.Error("Engine is not ready yet. Please wait.")
            return
        }

        _uiState.value = ParserUiState.Loading

        viewModelScope.launch(Dispatchers.Default) {
            when (val result = syntaxEngine.parse(text)) {
                is PamResult.Success -> {
                    _uiState.value = ParserUiState.Success(result.data)
                }
                is PamResult.Error -> {
                    // Try to display the technical detail if user messaging fails
                    val message = result.error.userMessage.takeIf { it.isNotBlank() }
                        ?: result.error.cause?.localizedMessage
                        ?: "Unknown parsing error."
                    _uiState.value = ParserUiState.Error(message)
                }
            }
        }
    }

    fun playAudio(text: String) {
        if (text.isBlank()) return
        
        // Ensure TTS is off-loaded correctly if using Kokoro
        viewModelScope.launch(Dispatchers.Default) {
            val result = ttsEngine.speak(text = text, language = "ar")
            if (result is PamResult.Error) {
                _uiState.value = ParserUiState.Error("Audio failed: ${result.error.userMessage}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        syntaxEngine.release()
        ttsEngine.release()
    }
}
