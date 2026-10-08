package com.postsaimanager.feature.chat

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.usecase.ChatErrorAction
import com.postsaimanager.core.domain.usecase.ChatTurn
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ChatErrorMappingTest {

    @Test
    @DisplayName("a model that cannot chat shows the app's own wording and keeps the action that opens the AI models screen")
    fun `cannot chat uses the translated message`() {
        val error = chatErrorOf(ChatTurn.Failed("English fallback", ChatErrorAction.CHOOSE_CHAT_MODEL))

        assertThat(error.action).isEqualTo(ChatErrorAction.CHOOSE_CHAT_MODEL)
        assertThat(error.messageRes).isEqualTo(R.string.chat_error_model_cannot_chat)
    }

    @Test
    @DisplayName("any other failure keeps the message the use case gave")
    fun `other failures keep their message`() {
        val error = chatErrorOf(ChatTurn.Failed("The engine stopped", ChatErrorAction.RETRY))

        assertThat(error.message).isEqualTo("The engine stopped")
        assertThat(error.messageRes).isNull()
        assertThat(error.action).isEqualTo(ChatErrorAction.RETRY)
    }
}
