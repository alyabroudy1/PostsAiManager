package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.ToolActionCall
import com.postsaimanager.core.domain.skills.ActionCardRecord
import com.postsaimanager.core.domain.skills.ActionCardTrace
import com.postsaimanager.core.domain.skills.ActionField
import com.postsaimanager.core.domain.skills.FieldStatus
import com.postsaimanager.core.domain.skills.LoadGroundingSourcesUseCase
import com.postsaimanager.core.domain.skills.ObserveStoredActionCardsUseCase
import com.postsaimanager.core.domain.skills.SaveActionCardStateUseCase
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ToolExchange
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileFactRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.postsaimanager.core.domain.skills.ObserveToolActionsUseCase
import com.postsaimanager.core.domain.skills.ActionResult
import com.postsaimanager.core.domain.skills.AgentAction
import com.postsaimanager.core.domain.skills.ConfirmActionUseCase
import com.postsaimanager.core.domain.skills.ConfirmOutcome
import com.postsaimanager.core.domain.skills.FieldCheck
import com.postsaimanager.core.domain.skills.InvalidReason
import com.postsaimanager.core.domain.skills.ProposeActionUseCase
import com.postsaimanager.core.domain.skills.ProposedAction
import com.postsaimanager.core.testing.MainDispatcherExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDateTime

@ExtendWith(MainDispatcherExtension::class)
class ActionCardsViewModelTest {

    private val now = LocalDateTime.of(2026, 10, 7, 12, 0)
    private val email = AgentAction.SendEmail("a@b.de", "Subject", "Body")
    private val checks = mapOf(ActionField.TO to FieldCheck.NOT_FOUND, ActionField.SUBJECT to FieldCheck.GROUNDED)

    private val propose = mockk<ProposeActionUseCase>()
    private val confirm = mockk<ConfirmActionUseCase>()

    init {
        coEvery { propose(any(), any(), any(), any()) } answers { ProposedAction(firstArg(), secondArg(), checks) }
        coEvery { confirm(any(), any(), any()) } returns ConfirmOutcome.Executed(ActionResult.Succeeded())
    }

    /** What the `:inference` process delivers over AIDL: the model's `run_intent` calls. */
    private val toolCalls = MutableSharedFlow<ToolActionCall>(extraBufferCapacity = 8)
    private val engine = mockk<ChatEngine> { every { toolActions } returns toolCalls }

    /** The stored conversation: what survives when the process dies. */
    private val conversations = FakeConversationRepository()

    private fun viewModel(documentId: String? = "d1", proposing: ProposeActionUseCase = propose) = ActionCardsViewModel(
        SavedStateHandle(if (documentId == null) emptyMap() else mapOf("documentId" to documentId)),
        proposing,
        confirm,
        ObserveToolActionsUseCase(engine),
        ObserveStoredActionCardsUseCase(conversations),
        SaveActionCardStateUseCase(conversations),
    )

    /** The grounding of a real [ProposeActionUseCase] over an empty letter (a reminder in the past is flagged by the clock alone). */
    private fun realPropose() = ProposeActionUseCase(LoadGroundingSourcesUseCase(FakeDocumentRepository(), FakeProfileRepository(), FakeProfileFactRepository()))

    /** A stored assistant reply that made one `run_intent` call, as the engine stores it. */
    private fun storeReply(intent: String, parameters: String, id: String = "m1") = runBlocking {
        val arguments = """{"intent":"$intent","parameters":"${parameters.replace("\"", "\\\"")}"}"""
        conversations.addMessage(
            AiMessage(
                id = id, conversationId = "conv-d1", role = MessageRole.ASSISTANT, content = "Check the card.", createdAt = 1L,
                toolTrace = listOf(ToolExchange("run_intent", arguments, """{"status":"shown"}""")),
            ),
        )
    }

    private fun storedRecord(id: String = "m1", copy: Int = 0): ActionCardRecord? = runBlocking {
        val message = conversations.getMessages("conv-d1").first().first { it.id == id }
        ActionCardTrace.cardsOf(message).firstOrNull { it.key.copy == copy }?.record
    }

    private val reminderParams = """{"message":"Pay","year":2099,"month":3,"day":4,"hour":9,"minute":30}"""

    private val emailCall = ToolActionCall(
        intent = "send_email",
        parametersJson = """{"extra_email":"a@b.de","extra_subject":"Subject","extra_text":"Body"}""",
        documentId = null,
    )

    @Test
    fun `a tool call from the model is rebuilt, checked against the chat's letter and shown as a card, running nothing`() {
        val vm = viewModel()
        vm.updateUserMessages(listOf("write to them"))

        toolCalls.tryEmit(emailCall)

        assertThat(vm.only().action).isEqualTo(email)
        assertThat(vm.only().status).isEqualTo(ActionCardStatus.PENDING)
        coVerify { propose(email, "d1", listOf("write to them"), any()) }
        coVerify(exactly = 0) { confirm(any(), any(), any()) }
    }

    @Test
    fun `in a chat over all letters the card is checked against the letter the reply was about, or none`() {
        val vm = viewModel(documentId = null)

        toolCalls.tryEmit(emailCall.copy(documentId = "cited"))
        toolCalls.tryEmit(emailCall.copy(parametersJson = emailCall.parametersJson.replace("Body", "Other")))

        coVerify { propose(email, "cited", any(), any()) }
        coVerify { propose(AgentAction.SendEmail("a@b.de", "Subject", "Other"), null, any(), any()) }
        assertThat(vm.cards.value).hasSize(2)
    }

    @Test
    fun `a tool call that does not parse, or only reads the clock, makes no card`() {
        val vm = viewModel()

        toolCalls.tryEmit(ToolActionCall("send_email", """{"extra_subject":"no address"}""", null))
        toolCalls.tryEmit(ToolActionCall("get_current_date_and_time", "{}", null))
        toolCalls.tryEmit(ToolActionCall("no_such_intent", "{}", null))

        assertThat(vm.cards.value).isEmpty()
    }

    private fun ActionCardsViewModel.only(): ActionCardState = cards.value.single()

    @Test
    fun `a proposed action becomes a card with its fields and its flags, and nothing runs`() {
        val vm = viewModel()

        vm.propose(email, listOf("write to them"), now)

        val card = vm.only()
        assertThat(card.status).isEqualTo(ActionCardStatus.PENDING)
        assertThat(card.values).containsExactly(ActionField.TO, "a@b.de", ActionField.SUBJECT, "Subject", ActionField.BODY, "Body")
        assertThat(card.checkOf(ActionField.TO)).isEqualTo(FieldCheck.NOT_FOUND)
        assertThat(card.hasFlags).isTrue()
        coVerify { propose(email, "d1", listOf("write to them"), now) }
        coVerify(exactly = 0) { confirm(any(), any(), any()) }
    }

    @Test
    fun `reading the clock needs no card`() {
        val vm = viewModel()

        vm.propose(AgentAction.GetDateTime, emptyList(), now)

        assertThat(vm.cards.value).isEmpty()
    }

    @Test
    fun `Open runs the card's fields through confirm and the card ends opened`() {
        val vm = viewModel()
        vm.propose(email, emptyList(), now)

        vm.open(vm.only().id, now)

        coVerify(exactly = 1) { confirm(any(), match { it[ActionField.TO] == "a@b.de" && it[ActionField.BODY] == "Body" }, now) }
        assertThat(vm.only().status).isEqualTo(ActionCardStatus.OPENED)
    }

    @Test
    fun `a final card cannot be opened again`() {
        val vm = viewModel()
        vm.propose(email, emptyList(), now)
        val id = vm.only().id
        vm.open(id, now)

        vm.open(id, now)
        vm.cancel(id)

        coVerify(exactly = 1) { confirm(any(), any(), any()) }
        assertThat(vm.only().status).isEqualTo(ActionCardStatus.OPENED)
    }

    @Test
    fun `Cancel ends the card without ever calling confirm`() {
        val vm = viewModel()
        vm.propose(email, emptyList(), now)
        val id = vm.only().id

        vm.cancel(id)
        vm.open(id, now)

        assertThat(vm.only().status).isEqualTo(ActionCardStatus.CANCELLED)
        coVerify(exactly = 0) { confirm(any(), any(), any()) }
    }

    @Test
    fun `an edited field is the user's, is what gets opened, and is no longer flagged`() {
        val vm = viewModel()
        vm.propose(email, emptyList(), now)
        val id = vm.only().id

        vm.startEditing(id)
        vm.changeField(id, ActionField.TO, "me@x.de")
        vm.open(id, now)

        assertThat(vm.only().checkOf(ActionField.TO)).isEqualTo(FieldCheck.USER_ENTERED)
        coVerify { confirm(any(), match { it[ActionField.TO] == "me@x.de" }, now) }
    }

    @Test
    fun `typing the proposal's own text back is not an edit`() {
        val vm = viewModel()
        vm.propose(email, emptyList(), now)
        val id = vm.only().id

        vm.changeField(id, ActionField.TO, "x")
        vm.changeField(id, ActionField.TO, "a@b.de")

        assertThat(vm.only().edited).isEmpty()
        assertThat(vm.only().checkOf(ActionField.TO)).isEqualTo(FieldCheck.NOT_FOUND)
    }

    @Test
    fun `fields that cannot make an action keep the card open at the fields with their errors`() {
        coEvery { confirm(any(), any(), any()) } returns ConfirmOutcome.Invalid(mapOf(ActionField.TO to InvalidReason.NOT_AN_EMAIL))
        val vm = viewModel()
        vm.propose(email, emptyList(), now)

        vm.open(vm.only().id, now)

        val card = vm.only()
        assertThat(card.status).isEqualTo(ActionCardStatus.PENDING)
        assertThat(card.editing).isTrue()
        assertThat(card.errors).containsExactly(ActionField.TO, InvalidReason.NOT_AN_EMAIL)
    }

    @Test
    fun `an error clears when its field is edited`() {
        coEvery { confirm(any(), any(), any()) } returns ConfirmOutcome.Invalid(mapOf(ActionField.TO to InvalidReason.NOT_AN_EMAIL))
        val vm = viewModel()
        vm.propose(email, emptyList(), now)
        val id = vm.only().id
        vm.open(id, now)

        vm.changeField(id, ActionField.TO, "ok@x.de")

        assertThat(vm.only().errors).isEmpty()
    }

    @Test
    fun `an action that could not be opened leaves the card open to try again`() {
        coEvery { confirm(any(), any(), any()) } returns ConfirmOutcome.Executed(ActionResult.Failed("No app"))
        val vm = viewModel()
        vm.propose(email, emptyList(), now)

        vm.open(vm.only().id, now)

        assertThat(vm.only().status).isEqualTo(ActionCardStatus.PENDING)
        assertThat(vm.only().openFailed).isTrue()
    }

    @Test
    fun `cards of two proposals are kept apart`() {
        val vm = viewModel()
        vm.propose(email, emptyList(), now)
        vm.propose(AgentAction.ScheduleReminder(now.plusDays(1), "Pay", null), emptyList(), now)

        vm.cancel(vm.cards.value.first().id)

        assertThat(vm.cards.value.map { it.status }).containsExactly(ActionCardStatus.CANCELLED, ActionCardStatus.PENDING).inOrder()
    }

    @Test
    fun `a card's state is kept with its reply, edited values and when it was opened`() {
        val vm = viewModel()
        storeReply("send_email", emailCall.parametersJson)
        val id = vm.only().id
        assertThat(storedRecord()?.status).isEqualTo(ActionCardStatus.PENDING)

        vm.changeField(id, ActionField.TO, "me@x.de")
        vm.open(id, now)

        val record = storedRecord()!!
        assertThat(record.status).isEqualTo(ActionCardStatus.OPENED)
        assertThat(record.values["TO"]).isEqualTo("me@x.de")
        assertThat(record.original["TO"]).isEqualTo("a@b.de")
        assertThat(ActionCardTrace.timeOf(record.doneAt)).isEqualTo(now)
        assertThat(record.documentId).isEqualTo("d1")
    }

    @Test
    fun `after a restart the cards are rebuilt from the stored replies with their state and edits`() {
        val first = viewModel()
        storeReply("send_email", emailCall.parametersJson)
        first.startEditing(first.only().id)
        first.changeField(first.only().id, ActionField.TO, "me@x.de")
        first.cancel(first.only().id)

        val restarted = viewModel()

        val card = restarted.only()
        assertThat(card.status).isEqualTo(ActionCardStatus.CANCELLED)
        assertThat(card.values[ActionField.TO]).isEqualTo("me@x.de")
        assertThat(card.checkOf(ActionField.TO)).isEqualTo(FieldCheck.USER_ENTERED)
        assertThat(card.checkOf(ActionField.SUBJECT)).isEqualTo(FieldCheck.GROUNDED)
    }

    @Test
    fun `a cancelled card is restored as a pending card with its last values and opens again`() {
        val first = viewModel()
        storeReply("send_email", emailCall.parametersJson)
        first.changeField(first.only().id, ActionField.SUBJECT, "Edited")
        first.cancel(first.only().id)
        val vm = viewModel()
        val id = vm.only().id

        vm.restore(id)
        vm.open(id, now)

        coVerify { confirm(any(), match { it[ActionField.SUBJECT] == "Edited" }, now) }
        assertThat(vm.only().status).isEqualTo(ActionCardStatus.OPENED)
        assertThat(storedRecord()?.status).isEqualTo(ActionCardStatus.OPENED)
    }

    @Test
    fun `restoring stores the pending state, and only a cancelled card can be restored`() {
        val vm = viewModel()
        storeReply("send_email", emailCall.parametersJson)
        val id = vm.only().id

        vm.restore(id)
        assertThat(vm.only().status).isEqualTo(ActionCardStatus.PENDING)

        vm.cancel(id)
        assertThat(storedRecord()?.status).isEqualTo(ActionCardStatus.CANCELLED)
        vm.restore(id)
        assertThat(vm.only().status).isEqualTo(ActionCardStatus.PENDING)
        assertThat(storedRecord()?.status).isEqualTo(ActionCardStatus.PENDING)
    }

    @Test
    fun `Do again makes a fresh pending copy of an opened card with the same values, and survives a restart`() {
        val vm = viewModel()
        storeReply("send_email", emailCall.parametersJson)
        val id = vm.only().id
        vm.changeField(id, ActionField.BODY, "Changed")
        vm.open(id, now)

        vm.doAgain(id)

        assertThat(vm.cards.value.map { it.status }).containsExactly(ActionCardStatus.OPENED, ActionCardStatus.PENDING).inOrder()
        val copy = vm.cards.value.last()
        assertThat(copy.id).isNotEqualTo(id)
        assertThat(copy.values).isEqualTo(vm.cards.value.first().values)
        assertThat(copy.checkOf(ActionField.BODY)).isEqualTo(FieldCheck.USER_ENTERED)
        assertThat(storedRecord(copy = 1)?.status).isEqualTo(ActionCardStatus.PENDING)

        val restarted = viewModel()
        assertThat(restarted.cards.value.map { it.status }).containsExactly(ActionCardStatus.OPENED, ActionCardStatus.PENDING).inOrder()
        restarted.open(restarted.cards.value.last().id, now)
        coVerify(exactly = 2) { confirm(any(), match { it[ActionField.BODY] == "Changed" }, now) }
    }

    @Test
    fun `Do again is only for an opened card`() {
        val vm = viewModel()
        vm.propose(email, emptyList(), now)

        vm.doAgain(vm.only().id)

        assertThat(vm.cards.value).hasSize(1)
    }

    @Test
    fun `a rebuilt reminder whose time has passed is flagged, one in the future is not`() {
        storeReply("schedule_notification", """{"message":"Pay","year":2020,"month":3,"day":4,"hour":9,"minute":30}""", id = "past")
        storeReply("schedule_notification", reminderParams, id = "future")

        val vm = viewModel(proposing = realPropose())

        val (past, future) = vm.cards.value
        assertThat(past.checkOf(ActionField.AT)?.status).isEqualTo(FieldStatus.INVALID)
        assertThat(past.checkOf(ActionField.AT)?.reason).isEqualTo(InvalidReason.IN_THE_PAST)
        assertThat(future.checkOf(ActionField.AT)?.status).isNotEqualTo(FieldStatus.INVALID)
    }

    @Test
    fun `restoring a cancelled reminder recomputes the flags against the clock`() {
        val vm = viewModel(proposing = realPropose())
        storeReply("schedule_notification", """{"message":"Pay","year":2020,"month":3,"day":4,"hour":9,"minute":30}""")
        val id = vm.only().id
        vm.cancel(id)
        // an edit to a time ahead is the user's; a flag follows the values
        vm.restore(id)
        assertThat(vm.only().checkOf(ActionField.AT)?.status).isEqualTo(FieldStatus.INVALID)
    }

    @Test
    fun `a live card adopts its reply when it is stored, and is not shown twice`() {
        val vm = viewModel()
        toolCalls.tryEmit(emailCall)
        val live = vm.only().id

        storeReply("send_email", emailCall.parametersJson)

        assertThat(vm.cards.value).hasSize(1)
        assertThat(vm.only().id).isEqualTo(live)
        assertThat(vm.only().storedKey?.messageId).isEqualTo("m1")
        assertThat(storedRecord()?.status).isEqualTo(ActionCardStatus.PENDING)
    }

    @Test
    fun `a card goes when its reply is deleted`() {
        val vm = viewModel()
        storeReply("send_email", emailCall.parametersJson)
        assertThat(vm.cards.value).hasSize(1)

        runBlocking { conversations.deleteMessage("m1") }

        assertThat(vm.cards.value).isEmpty()
    }
}
