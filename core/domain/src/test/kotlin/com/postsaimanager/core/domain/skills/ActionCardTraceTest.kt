package com.postsaimanager.core.domain.skills

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ToolExchange
import com.postsaimanager.core.testing.FakeConversationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ActionCardTraceTest {

    private val runIntent = ToolExchange(
        "run_intent",
        """{"intent":"send_email","parameters":"{\"extra_email\":\"a@b.de\"}"}""",
        """{"status":"shown"}""",
    )
    private val skill = ToolExchange("load_skill", """{"skill_name":"x"}""", """{"skill_instructions":"y"}""")
    private val webview = ToolExchange("run_js", "{}", "{}", """{"webview":{"url":"u","aspectRatio":1.5}}""")

    private fun reply(vararg exchanges: ToolExchange) =
        AiMessage(id = "m1", conversationId = "c1", role = MessageRole.ASSISTANT, content = "ok", createdAt = 1, toolTrace = exchanges.toList())

    private val record = ActionCardRecord(
        status = ActionCardStatus.OPENED,
        values = mapOf("TO" to "me@x.de"),
        original = mapOf("TO" to "a@b.de"),
        documentId = "d1",
        doneAt = "2026-10-07T12:00",
    )

    @Test
    fun `a call that was never touched is one card without a record, the other calls are no cards`() {
        val cards = ActionCardTrace.cardsOf(reply(skill, runIntent, webview))

        val card = cards.single()
        assertThat(card.key).isEqualTo(ActionCardKey("c1", "m1", exchangeIndex = 1, copy = 0))
        assertThat(card.intent).isEqualTo("send_email")
        assertThat(card.parametersJson).isEqualTo("""{"extra_email":"a@b.de"}""")
        assertThat(card.record).isNull()
    }

    @Test
    fun `a record round-trips through the reply's trace`() {
        val stored = ActionCardTrace.withRecord(reply(skill, runIntent), exchangeIndex = 1, copy = 0, record = record)

        assertThat(ActionCardTrace.cardsOf(stored).single().record).isEqualTo(record)
        // the call itself and the other exchanges are untouched
        assertThat(stored.toolTrace[1].argumentsJson).isEqualTo(runIntent.argumentsJson)
        assertThat(stored.toolTrace[0]).isEqualTo(skill)
    }

    @Test
    fun `copies are kept in order beside the first card`() {
        val once = ActionCardTrace.withRecord(reply(runIntent), 0, 0, record)
        val twice = ActionCardTrace.withRecord(once, 0, 1, record.copy(status = ActionCardStatus.PENDING, doneAt = null))

        val cards = ActionCardTrace.cardsOf(twice)
        assertThat(cards.map { it.key.copy }).containsExactly(0, 1).inOrder()
        assertThat(cards.map { it.record?.status }).containsExactly(ActionCardStatus.OPENED, ActionCardStatus.PENDING).inOrder()
    }

    @Test
    fun `an unknown status reads as pending, and a user message has no cards`() {
        val broken = runIntent.copy(shownJson = """{"cards":[{"status":"NO_SUCH"}]}""")
        assertThat(ActionCardTrace.cardsOf(reply(broken)).single().record?.status).isEqualTo(ActionCardStatus.PENDING)

        val user = reply(runIntent).copy(role = MessageRole.USER)
        assertThat(ActionCardTrace.cardsOf(user)).isEmpty()
    }

    @Test
    fun `saving writes the record into the stored message and leaves what else is shown`() = runTest {
        val conversations = FakeConversationRepository()
        conversations.addMessage(reply(runIntent.copy(shownJson = """{"other":1}""")))

        SaveActionCardStateUseCase(conversations)(ActionCardKey("c1", "m1", 0, 0), record)

        val message = conversations.getMessages("c1").first().single()
        assertThat(message.toolTrace.single().shownJson).contains("\"other\":1")
        val observed = ObserveStoredActionCardsUseCase(conversations)("c1").first()
        assertThat(observed.cards.single().record).isEqualTo(record)
    }

    @Test
    fun `the user's own words come with the cards`() = runTest {
        val conversations = FakeConversationRepository()
        conversations.addMessage(AiMessage(id = "u1", conversationId = "c1", role = MessageRole.USER, content = "remind me", createdAt = 0))
        conversations.addMessage(reply(runIntent))

        val observed = ObserveStoredActionCardsUseCase(conversations)("c1").first()

        assertThat(observed.userMessages).containsExactly("remind me")
        assertThat(observed.cards).hasSize(1)
    }

    @Test
    fun `saving to a message that is gone does nothing`() = runTest {
        val conversations = FakeConversationRepository()

        SaveActionCardStateUseCase(conversations)(ActionCardKey("c1", "nope", 0, 0), record)

        assertThat(conversations.getMessages("c1").first()).isEmpty()
    }
}
