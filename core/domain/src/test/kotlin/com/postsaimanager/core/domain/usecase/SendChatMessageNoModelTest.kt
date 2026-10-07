package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.setup.DownloadActivity
import com.postsaimanager.core.model.DownloadSummary
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.FakeProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Chat before any model is installed: "still downloading" when it is on its way, "install one" when nothing is happening. */
class SendChatMessageNoModelTest {

    private fun useCase(summary: DownloadSummary?) = SendChatMessageUseCase(
        FakeConversationRepository(),
        FakeAiEngine(),
        FakeActiveModelProvider(path = null),
        BuildChatContextUseCase(FakeDocumentRepository(), FakeProfileRepository(), com.postsaimanager.core.testing.letterContactsFor()),
        RetrieveChunksUseCase(
            FakeDocumentChunkRepository(),
            FakeEmbeddingService(),
            ObserveChatVisibleDocumentsUseCase(FakeDocumentRepository()),
        ),
        object : DownloadActivity {
            override val summary: Flow<DownloadSummary?> = flowOf(summary)
        },
    )

    private suspend fun failure(summary: DownloadSummary?): ChatTurn.Failed =
        useCase(summary)("c", documentId = null, text = "hi").toList().filterIsInstance<ChatTurn.Failed>().single()

    @Test
    fun `while a model downloads chat says so instead of asking to install one`() = runTest {
        assertThat(failure(DownloadSummary(1, 2, 40)).action).isEqualTo(ChatErrorAction.MODEL_DOWNLOADING)
    }

    @Test
    fun `with nothing downloading chat asks to install a model`() = runTest {
        assertThat(failure(null).action).isEqualTo(ChatErrorAction.INSTALL_MODEL)
    }

    @Test
    fun `a failed download still asks to install a model`() = runTest {
        assertThat(failure(DownloadSummary(1, 2, 40, failed = true)).action).isEqualTo(ChatErrorAction.INSTALL_MODEL)
    }
}
