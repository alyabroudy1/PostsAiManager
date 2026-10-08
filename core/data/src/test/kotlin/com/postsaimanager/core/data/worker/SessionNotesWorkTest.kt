package com.postsaimanager.core.data.worker

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.usecase.ChatSessionEnd
import com.postsaimanager.core.domain.usecase.ChatSessionEnded
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The queued notes of an ended chat session: one work per session, and a bounded number of later tries. */
class SessionNotesWorkTest {

    private val event = ChatSessionEnded("c1", ChatSessionEnd.IDLE, startedAt = 1_000L)

    @Test
    @DisplayName("the same session is the same work (queued twice, written once); another session is another work")
    fun idempotentPerSession() {
        assertThat(SessionNotesWork.workName(event)).isEqualTo(SessionNotesWork.workName(event.copy(reason = ChatSessionEnd.LEFT)))
        assertThat(SessionNotesWork.workName(event)).isNotEqualTo(SessionNotesWork.workName(event.copy(startedAt = 2_000L)))
        assertThat(SessionNotesWork.workName(event)).isNotEqualTo(SessionNotesWork.workName(event.copy(conversationId = "c2")))
    }

    @Test
    @DisplayName("a deferred session is asked again until the limit, then left")
    fun boundedRetries() {
        assertThat(SessionNotesWork.shouldRetry(0)).isTrue()
        assertThat(SessionNotesWork.shouldRetry(SessionNotesWork.MAX_ATTEMPTS - 1)).isTrue()
        assertThat(SessionNotesWork.shouldRetry(SessionNotesWork.MAX_ATTEMPTS)).isFalse()
    }
}
