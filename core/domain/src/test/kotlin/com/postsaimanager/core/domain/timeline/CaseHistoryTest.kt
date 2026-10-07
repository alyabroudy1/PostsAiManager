package com.postsaimanager.core.domain.timeline

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.ProfileEvent
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

class CaseHistoryTest {

    private val zone = ZoneOffset.UTC
    private fun day(iso: String) = LocalDate.parse(iso).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun event(id: String, doc: String, kind: String, date: String, title: String) =
        ProfileEvent(id, doc, kind, day(date), day(date), title, caseId = "bg")

    private fun matter(vararg events: ProfileEvent) = CaseTimeline(
        Case("bg", "jc", "Bürgergeld application", createdAt = 1),
        events.sortedByDescending { it.eventDate },
    )

    private fun lines(case: CaseTimeline?, doc: String = "d3") = CaseHistory.lines(case, doc, zone = zone)

    @Test
    fun `the other events of the case come oldest first, one line each with date, kind label and title`() {
        val case = matter(
            event("e1", "d1", EventKinds.APPLICATION_FILED, "2026-08-20", "Bürgergeld application received"),
            event("e2", "d2", EventKinds.APPROVAL, "2026-09-10", "Bürgergeld approved from 1 Sep"),
            event("e3", "d3", EventKinds.DOCUMENTS_REQUESTED, "2026-11-03", "Proof of income requested"),
        )

        assertThat(lines(case)).containsExactly(
            "- 2026-08-20 Application filed: Bürgergeld application received",
            "- 2026-09-10 Approval: Bürgergeld approved from 1 Sep",
        ).inOrder()
    }

    @Test
    fun `the letter's own events are not repeated`() {
        val case = matter(event("e1", "d1", EventKinds.APPROVAL, "2026-09-10", "Approved"), event("e2", "d3", EventKinds.REJECTION, "2026-12-01", "Rejected"))

        assertThat(lines(case, doc = "d3")).containsExactly("- 2026-09-10 Approval: Approved")
        assertThat(lines(case, doc = "d1")).containsExactly("- 2026-12-01 Rejection: Rejected")
    }

    @Test
    fun `at most four, the most recent ones, still oldest first`() {
        val case = matter(
            event("e1", "d1", EventKinds.INFORMATION, "2026-01-01", "one"),
            event("e2", "d2", EventKinds.INFORMATION, "2026-02-01", "two"),
            event("e3", "d4", EventKinds.INFORMATION, "2026-03-01", "three"),
            event("e4", "d5", EventKinds.INFORMATION, "2026-04-01", "four"),
            event("e5", "d6", EventKinds.INFORMATION, "2026-05-01", "five"),
        )

        assertThat(lines(case).map { it.substringAfter(": ") }).containsExactly("two", "three", "four", "five").inOrder()
    }

    @Test
    fun `a long title is cut, an unknown kind reads as information, no title leaves the label alone`() {
        val case = matter(
            event("e1", "d1", "from_the_future", "2026-01-01", "x".repeat(200)),
            event("e2", "d2", EventKinds.APPROVAL, "2026-02-01", ""),
        )

        val result = lines(case)

        assertThat(result[0]).startsWith("- 2026-01-01 Information: ")
        assertThat(result[0].substringAfter(": ").length).isAtMost(CaseHistory.MAX_TITLE)
        assertThat(result[0]).endsWith("…")
        assertThat(result[1]).isEqualTo("- 2026-02-01 Approval")
    }

    @Test
    fun `a letter that is part of no case has no history`() {
        assertThat(lines(null)).isEmpty()
        assertThat(lines(matter(event("e3", "d3", EventKinds.APPROVAL, "2026-01-01", "only this letter")))).isEmpty()
    }
}
