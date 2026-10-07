package com.postsaimanager.feature.profiles

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.designsystem.component.NoteActions
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.NoteSource
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.Relationship
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "What the assistant remembers" on a person's profile, drawn for real: the notes of a household person, and only of one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileNotesUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val added = mutableListOf<String>()
    private val pinned = mutableListOf<Pair<String, Boolean>>()
    private val actions = NoteActions(add = { added += it }, pin = { id, value -> pinned += id to value })

    private fun profile(kind: ProfileKind = ProfileKind.PERSON, role: HouseholdRole? = HouseholdRole.MEMBER) = Profile(
        id = "maria", kind = kind, householdRole = role, name = "Maria", relationship = Relationship.CHILD.takeIf { role == HouseholdRole.MEMBER },
        createdAt = 0, modifiedAt = 0,
    )

    private fun note(id: String, text: String, pinned: Boolean = false) =
        DocumentNote(id, null, text, NoteSource.AI, createdAt = 0, updatedAt = 0, pinned = pinned, profileId = "maria")

    private fun show(draft: Profile, notes: List<DocumentNote>, isNew: Boolean = false) = compose.setContent {
        MaterialTheme {
            ProfileDetailContent(
                state = ProfileDetailUiState(draft = draft, loaded = true, isNew = isNew),
                availableKeys = FormDataKeys.ALL,
                snackbarHostState = SnackbarHostState(),
                onNavigateBack = {},
                onUpdate = {},
                onKind = {},
                onRole = {},
                onRelationship = {},
                onSave = {},
                detailActions = SavedDetailActions(save = { _, _ -> }, delete = {}),
                notes = notes,
                noteActions = actions,
            )
        }
    }

    @Test
    fun `a household person's profile lists the notes the assistant keeps about them`() {
        show(profile(), listOf(note("n1", "Works part-time"), note("n2", "Has swimming on Tuesdays", pinned = true)))

        compose.onNodeWithText("What the assistant remembers").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Works part-time").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Has swimming on Tuesdays").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Pin note").performScrollTo().performClick()
        assertThat(pinned).containsExactly("n1" to true)
    }

    @Test
    fun `with no notes it says so, and Add a note writes one for the person`() {
        show(profile(), emptyList())

        compose.onNodeWithText("Nothing yet. Notes appear here after you finish a chat about all your documents.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add a note").performScrollTo().performClick()
        // The profile's own fields are editable too: the note's editor is the focused one in the dialog.
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput("Needs glasses")
        // The profile's own Save is on the screen too; the dialog's is the last one.
        compose.onAllNodesWithText("Save").onLast().performClick()

        assertThat(added).containsExactly("Needs glasses")
    }

    @Test
    fun `an organisation, a person outside the household and a profile not yet saved have no notes section`() {
        show(profile(kind = ProfileKind.ORGANISATION, role = null), emptyList())
        compose.onNodeWithText("What the assistant remembers").assertDoesNotExist()
    }

    @Test
    fun `a person outside the household has no notes section`() {
        show(profile(role = null), emptyList())
        compose.onNodeWithText("What the assistant remembers").assertDoesNotExist()
    }

    @Test
    fun `a profile not yet saved has no notes section`() {
        show(profile(), emptyList(), isNew = true)
        compose.onNodeWithText("What the assistant remembers").assertDoesNotExist()
    }
}
