package com.postsaimanager.core.designsystem.component

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.EventSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The timeline section and the letter's "Part of" row, drawn for real (Robolectric, real string resources). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimelineViewsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val day = 86_400_000L
    private val opened = mutableListOf<String>()
    private val renamed = mutableListOf<Pair<String, String>>()
    private val labels = mapOf("application_filed" to "Application filed", "approval" to "Approval", "rejection" to "Rejection", "information" to "Information")
    private val kindLabel: (String) -> String = { labels[it] ?: it }

    private fun event(id: String, doc: String, kind: String, days: Long, title: String, source: EventSource = EventSource.DOCUMENT) =
        TimelineEventUi(id, doc, kind, days * day, title, source)

    private val jobcenter = TimelineCaseUi(
        caseId = "k1",
        title = "Bürgergeld",
        status = CaseStatus.REJECTED,
        letterCount = 3,
        organisationName = "Jobcenter",
        personNames = emptyList(),
        events = listOf(
            event("e3", "d3", "rejection", 200, "Abgelehnt"),
            event("e2", "d2", "approval", 110, "Bewilligt ab 1. Sep"),
            event("e1", "d1", "application_filed", 100, "Antrag eingegangen"),
        ),
    )

    private val timeline = TimelineUi(
        cases = listOf(jobcenter),
        other = listOf(event("x1", "d9", "information", 50, "Mitteilung zur Beratung").copy(context = "Stadtwerke")),
    )

    private fun show(ui: TimelineUi, focus: String? = null, direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TimelineSection(ui, kindLabel, onOpenDocument = { opened += it }, onRenameCase = { id, t -> renamed += id to t }, focusCaseId = focus)
                }
            }
        }
    }

    @Test
    fun `a case card shows the sender, title, letters, status and the latest event`() {
        show(timeline)

        compose.onNodeWithText("Bürgergeld").assertIsDisplayed()
        compose.onNodeWithText("From Jobcenter").assertIsDisplayed()
        compose.onNodeWithText("3 letters").assertIsDisplayed()
        compose.onNodeWithText("Rejected").assertIsDisplayed()
        compose.onNodeWithText("Other").assertIsDisplayed()
        compose.onNodeWithText("Mitteilung zur Beratung").assertIsDisplayed()
        compose.onNodeWithText("Stadtwerke").assertIsDisplayed()
    }

    @Test
    fun `the events are hidden until the card is opened and then listed`() {
        show(timeline)
        compose.onNodeWithTag("timeline_event_e2").assertDoesNotExist()

        compose.onNodeWithText("Bürgergeld").performClick()

        compose.onNodeWithTag("timeline_event_e3").assertIsDisplayed()
        compose.onNodeWithTag("timeline_event_e2").assertIsDisplayed()
        compose.onNodeWithTag("timeline_event_e1").assertIsDisplayed()
        compose.onNodeWithText("Bewilligt ab 1. Sep").assertIsDisplayed()
    }

    @Test
    fun `the focused case starts expanded`() {
        show(timeline, focus = "k1")

        compose.onNodeWithTag("timeline_event_e1").assertIsDisplayed()
    }

    @Test
    fun `tapping an event opens its letter`() {
        show(timeline, focus = "k1")

        compose.onNodeWithTag("timeline_event_e2").performClick()
        compose.onNodeWithTag("timeline_event_x1").performScrollTo().performClick()

        assertThat(opened).containsExactly("d2", "d9").inOrder()
    }

    @Test
    fun `an empty timeline says so`() {
        show(TimelineUi.EMPTY)

        compose.onNodeWithTag("timeline_empty").assertIsDisplayed()
    }

    @Test
    fun `a single-event matter drawn through the presenter is an event, not a card`() {
        val lone = com.postsaimanager.core.model.ProfileEvent(
            id = "e1", documentId = "d1", kind = "information", eventDate = 10 * day, recordedAt = 1, title = "Mitteilung",
            personProfileIds = listOf("maria"), organisationProfileId = "jc", caseId = "k1",
        )
        val case = com.postsaimanager.core.model.Case("k1", "jc", "Mitteilung", createdAt = 1)
        show(TimelinePresenter.present(listOf(TimelineCaseInput(case, listOf(lone))), emptyList(), { "Jobcenter" }, { "Maria" }, forOrganisation = false))

        compose.onAllNodesWithTag("timeline_case_k1").assertCountEquals(0)
        compose.onNodeWithTag("timeline_event_e1").assertIsDisplayed()
    }

    @Test
    fun `renaming a case reports the new name`() {
        show(timeline)

        compose.onNodeWithTag("timeline_case_menu_k1").performClick()
        compose.onNodeWithTag("timeline_rename_item").performClick()
        compose.onNodeWithTag("timeline_rename_field").performTextReplacement("Bürgergeld 2026")
        compose.onNodeWithTag("timeline_rename_save").performClick()

        assertThat(renamed).containsExactly("k1" to "Bürgergeld 2026")
    }

    @Test
    fun `more than five cases show five and Show all reveals the rest`() {
        val many = (1..7).map { jobcenter.copy(caseId = "k$it", title = "Matter $it", events = listOf(event("a$it", "d$it", "approval", it.toLong(), "T"), event("b$it", "e$it", "rejection", it + 1L, "T"))) }
        show(TimelineUi(cases = many))

        compose.onAllNodesWithTag("timeline_case_k6").assertCountEquals(0)
        compose.onNodeWithTag("timeline_cases_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("timeline_case_k6").assertExists()
    }

    @Test
    fun `the timeline draws right to left too`() {
        show(timeline, focus = "k1", direction = LayoutDirection.Rtl)

        compose.onNodeWithText("Bürgergeld").assertIsDisplayed()
        compose.onNodeWithTag("timeline_event_e3").assertIsDisplayed()
    }

    private fun document(currentDoc: String = "d3", profile: String? = "maria") = DocumentCaseUi(
        caseId = "k1", title = "Bürgergeld", status = CaseStatus.REJECTED,
        events = jobcenter.events + (1..4).map { event("m$it", "dm$it", "information", it.toLong(), "More $it") },
        currentDocumentId = currentDoc, openProfileId = profile,
    )

    private fun showRow(ui: DocumentCaseUi, direction: LayoutDirection = LayoutDirection.Ltr, onCase: (String, String) -> Unit = { _, _ -> }) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DocumentCaseRow(ui, kindLabel, onOpenCase = onCase, onOpenDocument = { opened += it })
                }
            }
        }
    }

    @Test
    fun `the letter shows Part of with the matter and its status`() {
        showRow(document())

        compose.onNodeWithText("Part of: Bürgergeld").assertIsDisplayed()
        compose.onNodeWithText("Rejected").assertIsDisplayed()
    }

    @Test
    fun `tapping the Part of title opens the matter on that profile`() {
        val taps = mutableListOf<Pair<String, String>>()
        showRow(document(), onCase = { profile, case -> taps += profile to case })

        compose.onNodeWithText("Part of: Bürgergeld").performClick()

        assertThat(taps).containsExactly("maria" to "k1")
    }

    @Test
    fun `the earlier and later events show five at most, then Show all`() {
        showRow(document())
        compose.onNodeWithTag("document_case_toggle").performClick()

        compose.onNodeWithText("This letter").assertIsDisplayed()
        compose.onAllNodesWithTag("timeline_event_m4").assertCountEquals(0)
        compose.onNodeWithTag("document_case_show_all").performScrollTo().performClick()
        compose.onNodeWithTag("timeline_event_m4").assertExists()
    }

    @Test
    fun `another letter of the matter opens, the current one does not`() {
        showRow(document(currentDoc = "d3"))
        compose.onNodeWithTag("document_case_toggle").performClick()

        compose.onNodeWithTag("timeline_event_e3").performClick()
        compose.onNodeWithTag("timeline_event_e2").performClick()

        assertThat(opened).containsExactly("d2")
    }

    @Test
    fun `the Part of row draws right to left`() {
        showRow(document(), direction = LayoutDirection.Rtl)

        compose.onNodeWithText("Part of: Bürgergeld").assertIsDisplayed()
    }
}
