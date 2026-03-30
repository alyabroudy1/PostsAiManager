package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val documentId: String? = savedStateHandle["documentId"]

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun sendMessage(text: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(text = text, isUser = true),
                isProcessing = true,
            )
        }

        viewModelScope.launch {
            // TODO: Replace with actual AI engine call
            // For now, provide intelligent placeholder responses
            delay(1500) // Simulate processing

            val response = generatePlaceholderResponse(text)

            _uiState.update { state ->
                state.copy(
                    messages = state.messages + ChatMessage(text = response, isUser = false),
                    isProcessing = false,
                )
            }
        }
    }

    private fun generatePlaceholderResponse(userMessage: String): String {
        val lower = userMessage.lowercase()
        return when {
            "summarize" in lower || "zusammenfass" in lower ->
                "I'll summarize this document once the AI model is configured. Go to Settings → AI Models to download a local model or configure a remote API."

            "deadline" in lower || "frist" in lower ->
                "To find deadlines, I need to analyze the document's extracted data. Make sure the document has been processed (OCR + extraction) first."

            "draft" in lower || "antwort" in lower || "response" in lower ->
                "I can help draft a response once the AI engine is connected. The draft will consider the document's context, sender, and subject."

            "sender" in lower || "absender" in lower || "who" in lower ->
                "Sender information is extracted during document processing. Check the 'Extracted' tab on the document detail screen."

            else ->
                "I'm Posts AI Manager's assistant. I can help you:\n\n" +
                    "• Summarize documents\n" +
                    "• Find deadlines and dates\n" +
                    "• Draft response letters\n" +
                    "• Identify senders and references\n\n" +
                    "Connect an AI model in Settings to unlock full capabilities."
        }
    }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
)

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)
