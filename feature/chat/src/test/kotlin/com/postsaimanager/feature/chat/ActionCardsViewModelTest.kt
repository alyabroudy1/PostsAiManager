package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.ToolActionCall
import com.postsaimanager.core.domain.skills.ActionField
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

    private fun viewModel(documentId: String? = "d1") = ActionCardsViewModel(
        SavedStateHandle(if (documentId == null) emptyMap() else mapOf("documentId" to documentId)),
        propose,
        confirm,
        ObserveToolActionsUseCase(engine),
    )

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
}
