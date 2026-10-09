package com.postsaimanager.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.EventSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the user can do on a timeline: edit, delete and add an event, set a matter's status, add a letter to a matter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimelineEditUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val day = 86_400_000L
    private val labels = mapOf("approval" to "Approval", "rejection" to "Rejection", "information" to "Information")
    private val kindLabel: (String) -> String = { labels[it] ?: it }

    private class Recorder {
        val edited = mutableListOf<List<Any>>()
        val deleted = mutableListOf<String>()
        val statuses = mutableListOf<Pair<String, CaseStatus?>>()
        val added = mutableListOf<List<Any>>()

        fun edits() = TimelineEdits(
            kindIds = listOf("approval", "rejection", "information"),
            onEditEvent = { id, kind, date, title -> edited += listOf(id, kind, date, title) },
            onDeleteEvent = { deleted += it },
            onSetStatus = { id, status -> statuses += id to status },
            onAddEvent = { kind, date, title -> added += listOf(kind, date, title) },
        )
    }

    private val approval = TimelineEventUi("e1", "d1", "approval", 100 * day, "Bewilligt", EventSource.DOCUMENT)
    private val card = TimelineCaseUi(
        caseId = "k1", title = "Bürgergeld", status = CaseStatus.APPROVED, letterCount = 1, organisationName = "Jobcenter",
        personNames = emptyList(), events = listOf(approval),
    )
    private val ui = DocumentCaseUi("k1", "Bürgergeld", CaseStatus.APPROVED, listOf(approval), "d1", "jc")

    @Test
    fun `a letter in no matter says so and offers to add it to one`() {
        var added = 0
        compose.setContent { MaterialTheme { DocumentCaseNoneRow(onAdd = { added++ }) } }

        compose.onNodeWithText("Part of: none").assertIsDisplayed()
        compose.onNodeWithTag("document_case_add").performClick()

        assertThat(added).isEqualTo(1)
    }

    @Test
    fun `an event's menu edits its kind and text, and the save hands them over`() {
        val rec = Recorder()
        compose.setContent {
            MaterialTheme { TimelineSection(TimelineUi(listOf(card)), kindLabel, onOpenDocument = {}, onRenameCase = { _, _ -> }, focusCaseId = "k1", edits = rec.edits()) }
        }

        compose.onNodeWithTag("timeline_event_menu_e1").performClick()
        compose.onNodeWithTag("timeline_event_edit_e1").performClick()
        compose.onNodeWithTag("event_kind_rejection").performClick()
        compose.onNodeWithTag("event_text_field").performTextClearance()
        compose.onNodeWithTag("event_text_field").performTextInput("Abgelehnt")
        compose.onNodeWithTag("event_save").performClick()

        val saved = rec.edited.single()
        assertThat(saved[0]).isEqualTo("e1")
        assertThat(saved[1]).isEqualTo("rejection")
        assertThat(saved[2]).isEqualTo(100 * day)
        assertThat(saved[3]).isEqualTo("Abgelehnt")
    }

    @Test
    fun `an event's menu deletes it`() {
        val rec = Recorder()
        compose.setContent {
            MaterialTheme { TimelineSection(TimelineUi(listOf(card)), kindLabel, onOpenDocument = {}, onRenameCase = { _, _ -> }, focusCaseId = "k1", edits = rec.edits()) }
        }

        compose.onNodeWithTag("timeline_event_menu_e1").performClick()
        compose.onNodeWithTag("timeline_event_delete_e1").performClick()

        assertThat(rec.deleted).containsExactly("e1")
    }

    @Test
    fun `without edits there is no event menu`() {
        compose.setContent {
            MaterialTheme { TimelineSection(TimelineUi(listOf(card)), kindLabel, onOpenDocument = {}, onRenameCase = { _, _ -> }, focusCaseId = "k1") }
        }

        compose.onNodeWithTag("timeline_event_menu_e1").assertDoesNotExist()
    }

    @Test
    fun `the status dialog starts on Automatic and hands over a chosen status or null`() {
        val rec = Recorder()
        compose.setContent {
            MaterialTheme { TimelineSection(TimelineUi(listOf(card)), kindLabel, onOpenDocument = {}, onRenameCase = { _, _ -> }, edits = rec.edits()) }
        }

        compose.onNodeWithTag("timeline_case_menu_k1").performClick()
        compose.onNodeWithTag("timeline_status_item").performClick()
        compose.onNodeWithTag("status_REJECTED").performClick()
        compose.onNodeWithTag("status_save").performClick()
        compose.onNodeWithTag("timeline_case_menu_k1").performClick()
        compose.onNodeWithTag("timeline_status_item").performClick()
        compose.onNodeWithTag("status_automatic").performClick()
        compose.onNodeWithTag("status_save").performClick()

        assertThat(rec.statuses).containsExactly("k1" to CaseStatus.REJECTED, "k1" to null).inOrder()
    }

    @Test
    fun `the letter's row adds an event to the letter and sets the matter's status`() {
        val rec = Recorder()
        compose.setContent {
            MaterialTheme { DocumentCaseRow(ui, kindLabel, onOpenCase = { _, _ -> }, onOpenDocument = {}, edits = rec.edits()) }
        }

        compose.onNodeWithTag("document_case_menu").performClick()
        compose.onNodeWithTag("document_case_add_event_item").performClick()
        compose.onNodeWithTag("event_kind_information").performClick()
        compose.onNodeWithTag("event_text_field").performTextInput("Phoned the office")
        compose.onNodeWithTag("event_save").performClick()

        val added = rec.added.single()
        assertThat(added[0]).isEqualTo("information")
        assertThat(added[2]).isEqualTo("Phoned the office")
    }

    @Test
    fun `an event needs its text before it can be saved`() {
        val rec = Recorder()
        compose.setContent {
            MaterialTheme { DocumentCaseRow(ui, kindLabel, onOpenCase = { _, _ -> }, onOpenDocument = {}, edits = rec.edits()) }
        }

        compose.onNodeWithTag("document_case_menu").performClick()
        compose.onNodeWithTag("document_case_add_event_item").performClick()
        compose.onNodeWithTag("event_save").performClick()

        assertThat(rec.added).isEmpty()
    }
}
