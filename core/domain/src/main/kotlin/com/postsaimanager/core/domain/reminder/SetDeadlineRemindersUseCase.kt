package com.postsaimanager.core.domain.reminder

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.list.ObserveDocumentListItemsUseCase
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.domain.skills.ReminderScheduler
import com.postsaimanager.core.model.DocumentDateChip
import com.postsaimanager.core.model.DocumentListItem
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

/**
 * The Settings "Deadline reminders" switch: stores the choice, and the app's one [ReminderScheduler] does the rest. Off cancels every
 * deadline reminder. On schedules one per letter with a stored deadline, [DAYS_BEFORE] days before it (the list's "soon" window,
 * see [ObserveDocumentListItemsUseCase]) at [REMIND_AT]; a letter whose lead time is already past is reminded on the deadline day
 * itself, and a deadline that has passed gets none.
 *
 * Idempotent: turning on twice leaves one reminder per letter.
 */
class SetDeadlineRemindersUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val documents: ObserveDocumentListItemsUseCase,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {

    suspend operator fun invoke(enabled: Boolean): PamResult<Unit> {
        val stored = preferences.setNotificationsEnabled(enabled)
        if (stored is PamResult.Error) return stored
        scheduler.cancelDeadlines()
        if (!enabled) return stored

        for (item in documents().first()) scheduleFor(item)
        return stored
    }

    /**
     * A letter has just finished reading: when the switch is on and it has a deadline, schedules its reminder by the same rule as the
     * switch does. Does nothing when the switch is off, the letter is gone, or it has no upcoming deadline.
     */
    suspend fun onDocumentRead(documentId: String) {
        if (!preferences.getUserPreferences().first().notificationsEnabled) return
        val item = documents().first().firstOrNull { it.id == documentId } ?: return
        scheduleFor(item)
    }

    private suspend fun scheduleFor(item: DocumentListItem) {
        val due = item.dateChip.takeIf { it.kind == DocumentDateChip.Kind.DUE }?.date ?: return
        val at = remindAt(due, LocalDateTime.now(clock)) ?: return
        scheduler.scheduleDeadline(at, item.id, item.sender)
    }

    private fun remindAt(due: LocalDate, now: LocalDateTime): LocalDateTime? =
        listOf(due.minusDays(DAYS_BEFORE), due)
            .map { it.atTime(REMIND_AT) }
            .firstOrNull { it.isAfter(now) }

    companion object {
        /** The same three days the document list calls a deadline "soon". */
        const val DAYS_BEFORE = 3L

        val REMIND_AT: LocalTime = LocalTime.of(9, 0)
    }
}
