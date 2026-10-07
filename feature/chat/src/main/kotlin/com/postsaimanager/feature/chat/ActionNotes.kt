package com.postsaimanager.feature.chat

import android.content.Context
import android.text.format.DateFormat
import com.postsaimanager.core.designsystem.component.FriendlyDate
import com.postsaimanager.core.domain.memory.ForgetActionNoteUseCase
import com.postsaimanager.core.domain.memory.RecordActionNoteUseCase
import com.postsaimanager.core.domain.skills.ActionCardStatus
import com.postsaimanager.core.domain.skills.AgentAction
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** How the note of a confirmed action reads, in the user's language of the moment (the note is stored as written). */
interface ActionNoteWording {
    fun reminder(at: LocalDateTime, text: String): String
    fun calendarEvent(start: LocalDateTime, title: String): String
    fun email(to: String, openedOn: LocalDate, subject: String): String
}

/**
 * The wording over the string resources: "Reminder set for 8 Oct 09:00: Send the documents", "Email to jobcenter@example.de opened on
 * 7 Oct: Documents". Dates come from [FriendlyDate.format] (a month name in the user's own order, never digits alone) and the time
 * from the locale's short time format.
 */
class AndroidActionNoteWording @Inject constructor(
    @ApplicationContext private val context: Context,
) : ActionNoteWording {

    private val locale: Locale get() = context.resources.configuration.locales[0] ?: Locale.getDefault()

    private fun date(date: LocalDate, today: LocalDate = LocalDate.now()) = FriendlyDate.format(date, date.year != today.year, locale)

    private fun dateTime(at: LocalDateTime): String {
        val time = DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale).format(at)
        return context.getString(R.string.note_action_date_time, date(at.toLocalDate()), time)
    }

    override fun reminder(at: LocalDateTime, text: String): String = context.getString(R.string.note_action_reminder, dateTime(at), text)

    override fun calendarEvent(start: LocalDateTime, title: String): String =
        context.getString(R.string.note_action_calendar, dateTime(start), title)

    override fun email(to: String, openedOn: LocalDate, subject: String): String =
        if (subject.isBlank()) {
            context.getString(R.string.note_action_email_no_subject, to, date(openedOn))
        } else {
            context.getString(R.string.note_action_email, to, date(openedOn), subject)
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ActionNoteModule {
    @Binds
    abstract fun bindActionNoteWording(impl: AndroidActionNoteWording): ActionNoteWording
}

/**
 * Keeps the document memory in step with the action cards, with no AI: a card that was opened writes its note (one note per card,
 * so writing again replaces it), a card that is not opened (cancelled, or restored to pending) has none. The note says what happened
 * ("Reminder set for 8 Oct 09:00: ..."), formatted from the card's values the user confirmed.
 */
class ActionNotes @Inject constructor(
    private val record: RecordActionNoteUseCase,
    private val forget: ForgetActionNoteUseCase,
    private val wording: ActionNoteWording,
) {

    /** The note's text for [action] confirmed at [doneAt]; null for an action that is not shown on a card. */
    fun textOf(action: AgentAction, doneAt: LocalDateTime): String? = when (action) {
        is AgentAction.ScheduleReminder -> wording.reminder(action.at, action.text)
        is AgentAction.CreateCalendarEvent -> wording.calendarEvent(action.start, action.title)
        is AgentAction.SendEmail -> wording.email(action.to, doneAt.toLocalDate(), action.subject)
        AgentAction.GetDateTime -> null
    }

    /**
     * @param documentId the letter the note belongs to; null (a chat over all letters, no letter named) writes nothing
     * @param cardId the card's id: the note's reference, so the same card is one note
     */
    suspend fun sync(documentId: String?, cardId: String, action: AgentAction, status: ActionCardStatus, doneAt: LocalDateTime) {
        if (status != ActionCardStatus.OPENED) {
            forget(cardId)
            return
        }
        val letter = documentId ?: return
        val text = textOf(action, doneAt) ?: return
        record(letter, cardId, text)
    }
}
