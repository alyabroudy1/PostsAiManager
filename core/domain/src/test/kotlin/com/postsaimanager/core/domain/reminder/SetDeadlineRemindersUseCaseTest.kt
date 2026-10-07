package com.postsaimanager.core.domain.reminder

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.list.ObserveDocumentListItemsUseCase
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.domain.skills.ReminderScheduler
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentDateChip
import com.postsaimanager.core.model.DocumentListItem
import com.postsaimanager.core.model.DocumentListStatus
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.SourceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class SetDeadlineRemindersUseCaseTest {

    private val today = LocalDate.of(2026, 10, 7)
    private val clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private val scheduler = mockk<ReminderScheduler>(relaxed = true)
    private val preferences = mockk<UserPreferencesRepository> {
        coEvery { setNotificationsEnabled(any()) } returns PamResult.Success(Unit)
    }

    private fun item(id: String, kind: DocumentDateChip.Kind, date: LocalDate, sender: String? = "Finanzamt") = DocumentListItem(
        document = Document(
            id = id, title = id, status = DocumentStatus.EXTRACTED, sourceType = SourceType.CAMERA, createdAt = 0L, modifiedAt = 0L,
        ),
        firstPagePath = null,
        sender = sender,
        addressee = null,
        status = DocumentListStatus.Ready,
        dateChip = DocumentDateChip(kind, date),
        openActionCount = 0,
    )

    private fun useCase(vararg items: DocumentListItem) = SetDeadlineRemindersUseCase(
        preferences = preferences,
        documents = mockk<ObserveDocumentListItemsUseCase> { every { this@mockk.invoke("") } returns flowOf(items.toList()) },
        scheduler = scheduler,
        clock = clock,
    )

    @Test
    fun `switching off stores the choice and cancels the deadline reminders without scheduling any`() = runTest {
        val result = useCase(item("a", DocumentDateChip.Kind.DUE, today.plusDays(10)))(false)

        assertThat(result).isEqualTo(PamResult.Success(Unit))
        coVerify { preferences.setNotificationsEnabled(false) }
        coVerify(exactly = 1) { scheduler.cancelDeadlines() }
        coVerify(exactly = 0) { scheduler.scheduleDeadline(any(), any(), any()) }
    }

    @Test
    fun `switching on schedules a deadline three days before at nine in the morning`() = runTest {
        useCase(item("a", DocumentDateChip.Kind.DUE, today.plusDays(10)))(true)

        coVerify { preferences.setNotificationsEnabled(true) }
        coVerify(exactly = 1) { scheduler.scheduleDeadline(LocalDateTime.of(2026, 10, 14, 9, 0), "a", "Finanzamt") }
    }

    @Test
    fun `it cancels the old reminders before scheduling so switching on twice leaves one per letter`() = runTest {
        useCase(item("a", DocumentDateChip.Kind.DUE, today.plusDays(10)))(true)

        coVerifyOrder {
            scheduler.cancelDeadlines()
            scheduler.scheduleDeadline(any(), "a", any())
        }
    }

    @Test
    fun `a deadline inside the lead time is reminded on the deadline day`() = runTest {
        useCase(item("a", DocumentDateChip.Kind.DUE, today.plusDays(2)))(true)

        coVerify(exactly = 1) { scheduler.scheduleDeadline(LocalDateTime.of(2026, 10, 9, 9, 0), "a", any()) }
    }

    @Test
    fun `a deadline that has passed, and a letter without one, get no reminder`() = runTest {
        useCase(
            item("past", DocumentDateChip.Kind.DUE, today.minusDays(1)),
            item("today-after-nine", DocumentDateChip.Kind.DUE, today),
            item("letter", DocumentDateChip.Kind.LETTER, today.plusDays(20)),
            item("scanned", DocumentDateChip.Kind.SCANNED, today.plusDays(20)),
        )(true)

        coVerify(exactly = 0) { scheduler.scheduleDeadline(any(), any(), any()) }
    }

    @Test
    fun `a failed store schedules and cancels nothing`() = runTest {
        coEvery { preferences.setNotificationsEnabled(any()) } returns PamResult.Error(PamError.DatabaseError(RuntimeException("disk")))

        val result = useCase(item("a", DocumentDateChip.Kind.DUE, today.plusDays(10)))(true)

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        coVerify(exactly = 0) { scheduler.cancelDeadlines() }
        coVerify(exactly = 0) { scheduler.scheduleDeadline(any(), any(), any()) }
    }
}
