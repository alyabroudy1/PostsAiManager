package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.form.agent.FormFillAgent
import com.postsaimanager.core.domain.form.agent.FormRoute
import com.postsaimanager.core.domain.form.fill.FormMessageCodec
import com.postsaimanager.core.domain.usecase.ChatTurn
import com.postsaimanager.core.domain.usecase.GetDocumentPreviewUseCase
import com.postsaimanager.core.domain.usecase.ObserveSuggestedQuestionsUseCase
import com.postsaimanager.core.domain.usecase.SendChatMessageUseCase
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormFillingFlag
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.NormBox
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeFormFillRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.testing.testDocument
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** How the chat's view model drives the form conversation: entry, routing a typed message, chips, stored messages and the card. */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class ChatViewModelFormFillTest {

    private val documents = FakeDocumentRepository()
    private val conversations = FakeConversationRepository()
    private val fills = FakeFormFillRepository()
    private val formFill = mockk<FormFillAgent>(relaxed = true)
    private val sendChat = mockk<SendChatMessageUseCase>(relaxed = true)
    private val engine = mockk<AiEngine>(relaxed = true) {
        every { state } returns MutableStateFlow(ModelLoadState.Idle)
        every { isBusy } returns false
    }

    private fun viewModel(documentId: String? = "d1", fill: Boolean = false, flag: FormFillingFlag = FormFillingFlag.ON) = ChatViewModel(
        savedStateHandle = SavedStateHandle(
            buildMap {
                documentId?.let { put("documentId", it) }
                if (fill) put(ChatViewModel.ARG_FILL, true)
            },
        ),
        sendChatMessage = sendChat,
        conversationRepository = conversations,
        documentRepository = documents,
        engine = engine,
        observeInstalledModels = mockk(relaxed = true),
        selectActiveModel = mockk(relaxed = true),
        observeInferenceSettings = mockk(relaxed = true),
        updateInferenceSetting = mockk(relaxed = true),
        resetInferenceSettings = mockk(relaxed = true),
        unblockGpu = mockk(relaxed = true),
        getDocumentPreview = GetDocumentPreviewUseCase(documents, FakeDocumentChunkRepository()),
        observeSuggestedQuestions = ObserveSuggestedQuestionsUseCase(documents),
        formFill = formFill,
        formFills = fills,
        formFillingFlag = flag,
    )

    private fun chatAnswers() {
        every { sendChat.invoke(any(), any(), any(), any(), any(), any()) } returns flowOf(ChatTurn.Token("x"))
    }

    // ── Entry ──

    @Test
    fun `Help me fill it opens the chat with the fill started, and only once`() = runTest {
        val savedState = SavedStateHandle(mapOf("documentId" to "d1", ChatViewModel.ARG_FILL to true))
        fun vm() = ChatViewModel(
            savedStateHandle = savedState, sendChatMessage = sendChat, conversationRepository = conversations, documentRepository = documents,
            engine = engine, observeInstalledModels = mockk(relaxed = true), selectActiveModel = mockk(relaxed = true),
            observeInferenceSettings = mockk(relaxed = true), updateInferenceSetting = mockk(relaxed = true),
            resetInferenceSettings = mockk(relaxed = true), unblockGpu = mockk(relaxed = true),
            getDocumentPreview = GetDocumentPreviewUseCase(documents, FakeDocumentChunkRepository()),
            observeSuggestedQuestions = ObserveSuggestedQuestionsUseCase(documents), formFill = formFill, formFills = fills,
        )

        vm()
        coVerify(exactly = 1) { formFill.start("d1") }

        vm() // the screen was recreated: the saved state remembers the fill was started
        coVerify(exactly = 1) { formFill.start("d1") }
        coVerify(exactly = 1) { formFill.resume("d1") }
    }

    @Test
    fun `opening a document chat without the argument only resumes an interrupted fill`() = runTest {
        viewModel()

        coVerify { formFill.resume("d1") }
        coVerify(exactly = 0) { formFill.start(any()) }
    }

    @Test
    fun `the all-documents chat never touches the form conversation`() = runTest {
        val vm = viewModel(documentId = null)
        chatAnswers()

        vm.sendMessage("hello")

        coVerify(exactly = 0) { formFill.resume(any()) }
        coVerify(exactly = 0) { formFill.route(any(), any()) }
        io.mockk.verify { sendChat.invoke(any(), isNull(), eq("hello"), any(), any(), any()) }
    }

    // ── The feature flag ──

    @Test
    fun `with form filling off a document chat never reads a message for a fill request`() = runTest {
        chatAnswers()
        val vm = viewModel(flag = FormFillingFlag.OFF)

        vm.sendMessage("help me fill this in")

        coVerify(exactly = 0) { formFill.route(any(), any()) }
        io.mockk.verify { sendChat.invoke("conv-d1", "d1", "help me fill this in", any(), any(), any()) }
    }

    @Test
    fun `with form filling off opening the chat neither starts nor resumes a fill`() = runTest {
        viewModel(fill = true, flag = FormFillingFlag.OFF)

        coVerify(exactly = 0) { formFill.start(any()) }
        coVerify(exactly = 0) { formFill.resume(any()) }
    }

    // ── A typed message ──

    @Test
    fun `a message the form conversation takes is not sent to the chat`() = runTest {
        coEvery { formFill.route("d1", "nein") } returns FormRoute.HANDLED
        val vm = viewModel()

        vm.sendMessage("nein")

        coVerify { formFill.route("d1", "nein") }
        io.mockk.verify(exactly = 0) { sendChat.invoke(any(), any(), any(), any(), any(), any()) }
        assertThat(vm.uiState.value.isProcessing).isFalse()
    }

    @Test
    fun `an ordinary message goes to the grounded chat`() = runTest {
        coEvery { formFill.route("d1", "when is it due?") } returns FormRoute.NOT_FOR_FORM
        chatAnswers()
        val vm = viewModel()

        vm.sendMessage("when is it due?")

        io.mockk.verify { sendChat.invoke("conv-d1", "d1", "when is it due?", any(), any(), any()) }
    }

    @Test
    fun `while the agent runs every message is its own, the chat is not asked`() = runTest {
        coEvery { formFill.route("d1", "what is Haftung?") } returns FormRoute.HANDLED
        val vm = viewModel()

        vm.sendMessage("what is Haftung?")

        coVerify { formFill.route("d1", "what is Haftung?") }
        io.mockk.verify(exactly = 0) { sendChat.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a failing conversation shows the chat's error card instead of hanging`() = runTest {
        coEvery { formFill.route(any(), any()) } throws IllegalStateException("database is locked")
        val vm = viewModel()

        vm.sendMessage("nein")

        assertThat(vm.uiState.value.isProcessing).isFalse()
        assertThat(vm.uiState.value.error!!.message).isEqualTo("database is locked")
    }

    // ── Chips ──

    @Test
    fun `tapping a chip sends it to the conversation as the answer, with the label the user saw`() = runTest {
        val vm = viewModel()
        val chip = FormChip(FormChipAction.ANSWER, label = "Mittwoch 15:00 Uhr", arg = "Mittwoch 15:00 Uhr", fieldId = "f1")

        vm.onFormChip(chip, "Mittwoch 15:00 Uhr")

        coVerify { formFill.chip("d1", chip, "Mittwoch 15:00 Uhr") }
        assertThat(vm.uiState.value.isProcessing).isFalse()
    }

    // ── Stored messages and the card ──

    @Test
    fun `stored form messages come back as form messages, chat messages stay plain`() = runTest {
        val paused = FormMessage(FormMessageKind.STATUS, FormText.AGENT_PAUSED, chips = listOf(FormChip(FormChipAction.CONTINUE, labelCode = FormChipLabel.CONTINUE)))
        conversations.addMessage(FormMessageCodec.toMessage("m1", "conv-d1", 1, paused))
        conversations.addMessage(
            com.postsaimanager.core.model.AiMessage("m2", "conv-d1", com.postsaimanager.core.model.MessageRole.USER, "ja", createdAt = 2),
        )

        val vm = viewModel()

        val messages = vm.uiState.value.messages
        assertThat(messages.map { it.form }).containsExactly(paused, null).inOrder()
        assertThat(messages.first().isUser).isFalse()
        assertThat(messages.last().isUser).isTrue()
    }

    private fun step(id: String, role: com.postsaimanager.core.model.MessageRole, name: String, args: String? = null, content: String = "", at: Long) =
        com.postsaimanager.core.model.AiMessage(
            id = id, conversationId = "conv-d1", role = role, content = content, toolCallId = "call-$id", toolName = name, toolArgs = args,
            toolResult = if (role == com.postsaimanager.core.model.MessageRole.TOOL_RESULT) "{\"ok\":true}" else null, createdAt = at,
        )

    @Test
    fun `the agent's questions show with their chips, its protocol steps never show`() = runTest {
        val call = com.postsaimanager.core.model.MessageRole.TOOL_CALL
        val result = com.postsaimanager.core.model.MessageRole.TOOL_RESULT
        conversations.addMessage(step("a", call, "read_form", "{}", at = 1))
        conversations.addMessage(step("b", result, "read_form", at = 2))
        conversations.addMessage(step("c", call, "ask_user", "{\"question\":\"Wer?\",\"chips\":[\"Ahmad\",\"Ich\"]}", content = "Wer?", at = 3))
        conversations.addMessage(step("d", call, "show_fill_card", "{}", at = 4))
        conversations.addMessage(step("e", call, "show_on_page", "{\"field_id\":\"f2\"}", at = 5))

        val vm = viewModel()

        val shown = vm.uiState.value.messages
        assertThat(shown.map { it.id }).containsExactly("c", "d", "e").inOrder()
        assertThat(shown.map { it.form!!.kind }).containsExactly(FormMessageKind.QUESTION, FormMessageKind.CARD, FormMessageKind.PAGE).inOrder()
        assertThat(shown.first().text).isEqualTo("Wer?")
        assertThat(shown.first().form!!.chips.map { it.label }).containsExactly("Ahmad", "Ich").inOrder()
    }

    private fun field(id: String, page: Int, order: Int, value: String? = null, kind: FormFieldKind = FormFieldKind.TEXT) = FormField(
        id = id, formFillId = "fill-d1", documentId = "d1", page = page, labelText = id, labelBox = null,
        fillBox = NormBox(0.1f, 0.2f, 0.5f, 0.3f), kind = kind, value = value, orderIndex = order,
    )

    @Test
    fun `the fill card follows the stored fields live`() = runTest {
        fills.saveFill(FormFill("fill-d1", "d1", FormFillStatus.ASKING, createdAt = 1, updatedAt = 1))
        fills.saveFields("fill-d1", listOf(field("name", 1, 0, "Ahmad"), field("phone", 1, 1), field("sign", 2, 0, kind = FormFieldKind.SIGNATURE)))
        val vm = viewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.fillCard.collect { } }

        val card = vm.fillCard.value!!
        assertThat(card.fields.map { it.id }).containsExactly("name", "phone", "sign").inOrder()
        assertThat(card.progress.ready).isEqualTo(1)
        assertThat(card.progress.needYou).isEqualTo(1)
        assertThat(card.progress.signatures).isEqualTo(1)

        fills.setValue("phone", "0151", com.postsaimanager.core.model.FormValueSource.USER, com.postsaimanager.core.model.ReviewState.EDITED, null, 5)

        assertThat(vm.fillCard.value!!.progress.ready).isEqualTo(2)
        job.cancel()
    }

    @Test
    fun `there is no card before a fill was started`() = runTest {
        val vm = viewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.fillCard.collect { } }

        assertThat(vm.fillCard.value).isNull()
        job.cancel()
    }

    @Test
    fun `a page chip opens the page of the field with its box marked`() = runTest {
        documents.seed(testDocument(id = "d1", title = "Anmeldung"))
        documents.seedPages("d1", DocumentPage("p1", "d1", 1, "file:///1.jpg"), DocumentPage("p2", "d1", 2, "file:///2.jpg"))
        val vm = viewModel()

        vm.openFieldPreview(field("iban", page = 2, order = 0))

        val state = vm.preview.value!!
        assertThat(state.initialPageIndex).isEqualTo(1)
        val marked = state.preview!!.pages.single { it.pageNumber == 2 }.highlights
        assertThat(marked).hasSize(1)
        assertThat(marked.single().left).isEqualTo(0.1f)
        assertThat(state.preview!!.pages.single { it.pageNumber == 1 }.highlights).isEmpty()
    }

    @Test
    fun `leaving the chat cancels the work in the background`() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        var cancelled = false
        coEvery { formFill.chip(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val vm = viewModel()
        vm.onFormChip(FormChip(FormChipAction.CONTINUE, labelCode = FormChipLabel.CONTINUE), "Continue")
        assertThat(vm.uiState.value.isProcessing).isTrue()

        vm.stopGeneration()

        assertThat(cancelled).isTrue()
        assertThat(vm.uiState.value.isProcessing).isFalse()
    }
}
