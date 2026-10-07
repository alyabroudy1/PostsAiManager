package com.postsaimanager.core.domain.timeline

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.ProfileEvent
import org.junit.jupiter.api.Test

class CaseStatusDeriverTest {

    private var next = 0
    private fun event(kind: String, date: Long, recorded: Long = date) =
        ProfileEvent(id = "e${next++}", documentId = "d", kind = kind, eventDate = date, recordedAt = recorded, title = kind)

    private fun status(vararg events: ProfileEvent) = CaseStatusDeriver.derive(events.toList())

    @Test
    fun `no events, or only neutral ones, is open`() {
        assertThat(status()).isEqualTo(CaseStatus.OPEN)
        assertThat(status(event(EventKinds.INFORMATION, 1), event(EventKinds.PAYMENT_DEMAND, 2))).isEqualTo(CaseStatus.OPEN)
    }

    @Test
    fun `the latest deciding event is the status`() {
        assertThat(status(event(EventKinds.APPLICATION_FILED, 1), event(EventKinds.APPROVAL, 2))).isEqualTo(CaseStatus.APPROVED)
        assertThat(status(event(EventKinds.APPLICATION_FILED, 1), event(EventKinds.REJECTION, 2))).isEqualTo(CaseStatus.REJECTED)
        assertThat(status(event(EventKinds.CANCELLATION, 5))).isEqualTo(CaseStatus.CLOSED)
    }

    @Test
    fun `a neutral event after a decision does not change it`() {
        assertThat(status(event(EventKinds.APPROVAL, 2), event(EventKinds.INFORMATION, 3), event(EventKinds.DEADLINE_PASSED, 4)))
            .isEqualTo(CaseStatus.APPROVED)
    }

    @Test
    fun `an objection after a rejection opens the matter again, and a later rejection closes the round`() {
        assertThat(status(event(EventKinds.REJECTION, 2), event(EventKinds.OBJECTION, 3))).isEqualTo(CaseStatus.OPEN)
        assertThat(status(event(EventKinds.REJECTION, 2), event(EventKinds.OBJECTION, 3), event(EventKinds.REJECTION, 4))).isEqualTo(CaseStatus.REJECTED)
    }

    @Test
    fun `latest means the event date, not the order given or recorded`() {
        // The rejection letter was read first, the older approval later: the matter is still rejected.
        assertThat(status(event(EventKinds.REJECTION, date = 10, recorded = 1), event(EventKinds.APPROVAL, date = 5, recorded = 2)))
            .isEqualTo(CaseStatus.REJECTED)
    }

    @Test
    fun `an unknown kind says nothing`() {
        assertThat(status(event(EventKinds.REJECTION, 1), event("from-a-newer-build", 2))).isEqualTo(CaseStatus.REJECTED)
    }
}
