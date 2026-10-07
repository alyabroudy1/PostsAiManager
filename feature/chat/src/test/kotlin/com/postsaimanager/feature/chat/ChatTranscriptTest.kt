package com.postsaimanager.feature.chat

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.skills.ActionCardKey
import com.postsaimanager.core.domain.skills.ActionField
import com.postsaimanager.core.domain.skills.ActionForm
import com.postsaimanager.core.domain.skills.AgentAction
import com.postsaimanager.core.domain.skills.ProposedAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** Where the action cards sit in the chat: under the reply that proposed them, scrolling with it (Robolectric, real list). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
class ChatTranscriptTest {

    @get:Rule
    val compose = createComposeRule()

    private val reminder = AgentAction.ScheduleReminder(LocalDateTime.of(2030, 1, 2, 9, 0), "Call the Jobcenter", "d1")

    private fun card(id: String, replyId: String?, copy: Int = 0, status: ActionCardStatus = ActionCardStatus.PENDING) = ActionCardState(
        id = id,
        proposed = ProposedAction(reminder, null, emptyMap()),
        values = ActionForm.entries(reminder).toMap(),
        status = status,
        storedKey = replyId?.let { ActionCardKey("conversation", it, 0, copy) },
    )

    private fun user(id: String, text: String) = ChatMessage(id = id, text = text, isUser = true)
    private fun assistant(id: String, text: String) = ChatMessage(id = id, text = text, isUser = false)

    private fun build(messages: List<ChatMessage>, cards: List<ActionCardState>, live: Boolean = false) =
        ChatTimeline.build(messages, cards, error = false, live = live, thinking = false)

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
    private fun bottom(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.bottom

    /** Messages are their text, cards are the real card; every row is [rowHeight] tall at most so a long chat scrolls. */
    private fun show(entries: List<TimelineEntry>, listHeight: Int = 1500, onJump: () -> Unit = {}, listStateHolder: (androidx.compose.foundation.lazy.LazyListState) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                val listState = rememberLazyListState()
                listStateHolder(listState)
                ChatTranscript(entries = entries, listState = listState, modifier = Modifier.height(listHeight.dp), onJumpToCard = onJump) { entry ->
                    when (entry) {
                        is TimelineEntry.Card -> ActionCard(entry.card, onOpen = {}, onEdit = {}, onCancel = {}, onChange = { _: ActionField, _: String -> })
                        is TimelineEntry.Message -> Text(entry.message.text, modifier = Modifier.height(90.dp))
                        TimelineEntry.Live -> Text("streaming…")
                        else -> Unit
                    }
                }
            }
        }
    }

    @Test
    fun `a card renders under its reply and a later message appears below it`() {
        val messages = listOf(user("u1", "remind me"), assistant("a1", "Reminder ready"), user("u2", "thanks, and what else"))
        show(build(messages, listOf(card("c1", "a1"))))

        assertThat(top("Reminder")).isAtLeast(bottom("Reminder ready"))
        // Reversed layout: "below" is a larger y, so the later message sits after the card.
        val cardBottom = compose.onNodeWithTag("actionCard").fetchSemanticsNode().boundsInRoot.bottom
        assertThat(top("thanks, and what else")).isAtLeast(cardBottom)
    }

    @Test
    fun `a Do again copy sits under the same reply right after its original`() {
        val messages = listOf(assistant("a1", "Reminder ready"), user("u2", "later"))
        val entries = build(messages, listOf(card("c1", "a1", status = ActionCardStatus.OPENED), card("c2", "a1", copy = 1)))

        // Newest first: later, then (bottom to top) the copy, the original, the reply.
        assertThat(entries.map { (it as? TimelineEntry.Card)?.card?.id ?: (it as TimelineEntry.Message).message.id })
            .containsExactly("u2", "c2", "c1", "a1").inOrder()
    }

    @Test
    fun `a card under the streaming bubble moves under the reply once it is persisted`() {
        val live = card("c1", null)
        val before = build(listOf(user("u1", "remind me")), listOf(live), live = true)
        // Under the live bubble: the card is emitted BEFORE (below) it.
        assertThat(before.map { it::class }).containsExactly(
            TimelineEntry.Card::class, TimelineEntry.Live::class, TimelineEntry.Message::class,
        ).inOrder()

        val persisted = live.copy(storedKey = ActionCardKey("conversation", "a1", 0))
        val after = build(listOf(user("u1", "remind me"), assistant("a1", "Reminder ready")), listOf(persisted))
        assertThat(after.map { it::class }).containsExactly(
            TimelineEntry.Card::class, TimelineEntry.Message::class, TimelineEntry.Message::class,
        ).inOrder()
        // The card keeps its row key, so the list moves it instead of recreating it.
        assertThat(after.first().key).isEqualTo(before.first().key)
        assertThat((after[1] as TimelineEntry.Message).message.id).isEqualTo("a1")
    }

    @Test
    fun `a card under the streaming bubble is drawn below it, then below the persisted reply`() {
        val live = card("c1", null)
        var entries by mutableStateOf(build(listOf(user("u1", "remind me")), listOf(live), live = true))
        compose.setContent {
            MaterialTheme {
                ChatTranscript(entries = entries, listState = rememberLazyListState()) { entry ->
                    when (entry) {
                        is TimelineEntry.Card -> ActionCard(entry.card, onOpen = {}, onEdit = {}, onCancel = {}, onChange = { _: ActionField, _: String -> })
                        is TimelineEntry.Message -> Text(entry.message.text)
                        TimelineEntry.Live -> Text("streaming…")
                        else -> Unit
                    }
                }
            }
        }
        assertThat(top("Reminder")).isAtLeast(bottom("streaming…"))

        entries = build(
            listOf(user("u1", "remind me"), assistant("a1", "Reminder ready")),
            listOf(live.copy(storedKey = ActionCardKey("conversation", "a1", 0))),
        )
        compose.waitForIdle()

        assertThat(top("Reminder")).isAtLeast(bottom("Reminder ready"))
        compose.onNodeWithText("streaming…").assertDoesNotExist()
    }

    @Test
    fun `an action-only reply with a card is kept, an empty reply without anything is not`() {
        val onlyAction = assistant("a1", "")
        val nothing = assistant("a2", "")
        val kept = build(listOf(onlyAction, nothing), listOf(card("c1", "a1")))

        assertThat(kept.filterIsInstance<TimelineEntry.Message>().map { it.message.id }).containsExactly("a1")
    }

    @Test
    fun `the pill counts a pending card scrolled off, hides when it is visible, and its tap scrolls to the card`() {
        // The card is under the first reply; twenty more messages sit between it and the bottom of a short list.
        val messages = buildList {
            add(user("u0", "remind me"))
            add(assistant("a1", "Reminder ready"))
            repeat(20) { add(user("m$it", "message $it")) }
        }
        var jumped = 0
        show(build(messages, listOf(card("c1", "a1"))), listHeight = 400, onJump = { jumped++ })

        compose.onNodeWithTag(ACTION_WAITING_PILL_TAG).assertTextEquals("1 action waiting")

        compose.onNodeWithTag(ACTION_WAITING_PILL_TAG).performClick()
        compose.waitForIdle()

        assertThat(jumped).isEqualTo(1)
        compose.onNodeWithTag("actionCard").assertIsDisplayed()
        compose.onNodeWithTag(ACTION_WAITING_PILL_TAG).assertDoesNotExist()
    }

    @Test
    fun `the pill is not there while the pending card is visible, nor when nothing is pending`() {
        val messages = listOf(user("u0", "remind me"), assistant("a1", "Reminder ready"))
        show(build(messages, listOf(card("c1", "a1"))), listHeight = 600)
        compose.onNodeWithTag(ACTION_WAITING_PILL_TAG).assertDoesNotExist()
    }

    @Test
    fun `a resolved card off screen does not call for the pill, and two pending ones say so in the plural`() {
        val many = buildList {
            add(assistant("a1", "first"))
            add(assistant("a2", "second"))
            repeat(20) { add(user("m$it", "message $it")) }
        }
        show(build(many, listOf(card("c1", "a1", status = ActionCardStatus.OPENED))), listHeight = 400)
        compose.onNodeWithTag(ACTION_WAITING_PILL_TAG).assertDoesNotExist()
    }

    @Test
    fun `two pending cards off screen are counted together`() {
        val many = buildList {
            add(assistant("a1", "first"))
            add(assistant("a2", "second"))
            repeat(20) { add(user("m$it", "message $it")) }
        }
        show(build(many, listOf(card("c1", "a1"), card("c2", "a2"))), listHeight = 400)

        compose.onNodeWithTag(ACTION_WAITING_PILL_TAG).assertTextEquals("2 actions waiting")
    }

    @Test
    fun `the waiting helpers point at the newest pending card that is not visible`() {
        val entries = build(listOf(assistant("a1", "first"), assistant("a2", "second")), listOf(card("c1", "a1"), card("c2", "a2")))
        val c2 = entries.indexOfFirst { it.key == TimelineEntry.cardKey("c2") }

        assertThat(ChatTimeline.newestWaitingOffScreen(entries, emptySet())).isEqualTo(c2)
        assertThat(ChatTimeline.newestWaitingOffScreen(entries, setOf(TimelineEntry.cardKey("c2")))).isEqualTo(
            entries.indexOfFirst { it.key == TimelineEntry.cardKey("c1") },
        )
        assertThat(ChatTimeline.waitingOffScreen(entries, setOf(TimelineEntry.cardKey("c1"), TimelineEntry.cardKey("c2")))).isEqualTo(0)
    }
}
