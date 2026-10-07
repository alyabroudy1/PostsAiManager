package com.postsaimanager.feature.documents

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.NoteSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * "What the assistant remembers" drawn for real (Robolectric, real string resources): the list with each note's source and date, the
 * empty state, and add, edit, delete and pin, each reaching the actions with the note's id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentMemoryCardUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val added = mutableListOf<String>()
    private val edited = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<String>()
    private val pinned = mutableListOf<Pair<String, Boolean>>()

    private val actions = NoteActions(
        add = { added += it },
        edit = { id, text -> edited += id to text },
        delete = { deleted += it },
        pin = { id, value -> pinned += id to value },
    )

    private val today = LocalDate.of(2026, 10, 7)
    private val millis = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun note(id: String, text: String, source: NoteSource, pinned: Boolean = false) =
        DocumentNote(id, "d", text, source, createdAt = millis, updatedAt = millis, pinned = pinned)

    private fun show(vararg notes: DocumentNote) = compose.setContent {
        MaterialTheme { DocumentMemoryCard(notes.toList(), actions, today) }
    }

    @Test
    fun `the notes are listed with where each came from and the date, under the section's title`() {
        show(
            note("a", "Reminder set for 8 Oct 09:00: Pay", NoteSource.ACTION),
            note("b", "The user already paid", NoteSource.AI),
            note("c", "Call the Jobcenter", NoteSource.USER),
        )

        compose.onNodeWithText("What the assistant remembers").assertIsDisplayed()
        compose.onNodeWithText("Reminder set for 8 Oct 09:00: Pay").assertIsDisplayed()
        compose.onNodeWithText("The user already paid").assertIsDisplayed()
        compose.onNodeWithText("Call the Jobcenter").assertIsDisplayed()
        compose.onNodeWithContentDescription("From an action").assertExists()
        compose.onNodeWithContentDescription("From a chat").assertExists()
        compose.onNodeWithContentDescription("Written by you").assertExists()
        // The meta line: the source and the date, as a month name (never digits alone).
        compose.onNode(hasText("From a chat · ", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Oct", substring = true) and hasText("From a chat", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `with no notes it says so and still offers Add a note`() {
        show()

        compose.onNodeWithText("Nothing yet. Notes appear here after you open an action or finish a chat.").assertIsDisplayed()
        compose.onNodeWithText("Add a note").assertIsDisplayed()
    }

    @Test
    fun `Add a note writes the typed text as a new note`() {
        show()

        compose.onNodeWithText("Add a note").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Paid by transfer")
        compose.onNodeWithText("Save").performClick()

        assertThat(added).containsExactly("Paid by transfer")
        compose.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun `an empty note cannot be saved`() {
        show()

        compose.onNodeWithText("Add a note").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `Edit opens the note's text, and Save sends the new text with the note's id`() {
        show(note("n1", "The user already paid", NoteSource.AI))

        compose.onNodeWithContentDescription("More actions for this note").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("The user paid on 5 Oct")
        compose.onNodeWithText("Save").performClick()

        assertThat(edited).containsExactly("n1" to "The user paid on 5 Oct")
    }

    @Test
    fun `Delete removes the note by id`() {
        show(note("n1", "one", NoteSource.USER), note("n2", "two", NoteSource.USER))

        compose.onAllNodesWithContentDescription("More actions for this note")[1].performClick()
        compose.onNodeWithText("Delete").performClick()

        assertThat(deleted).containsExactly("n2")
    }

    @Test
    fun `Pin pins an unpinned note and unpins a pinned one`() {
        show(note("n1", "one", NoteSource.USER), note("n2", "two", NoteSource.USER, pinned = true))

        compose.onNodeWithContentDescription("Pin note").performClick()
        compose.onNodeWithContentDescription("Unpin note").performClick()

        assertThat(pinned).containsExactly("n1" to true, "n2" to false).inOrder()
    }
}
