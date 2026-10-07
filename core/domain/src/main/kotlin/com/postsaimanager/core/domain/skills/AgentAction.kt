package com.postsaimanager.core.domain.skills

import java.time.LocalDateTime

/**
 * Something a skill asks the app to do for the user. The model fills the values (from the conversation and the letter); the app
 * checks them ([ActionGrounding]), shows them on a card and fires the action only when the user taps Open ([ConfirmActionUseCase]).
 *
 * Mirrors the Gallery's intents that this app keeps: `send_email`, `create_calendar_event`, `schedule_notification` and
 * `get_current_date_and_time`. `send_sms` and `read_calendar_events` are not here: they need sensitive permissions (see
 * `documentation/agent-skills.md`, later options).
 */
sealed interface AgentAction {

    /** Whether the user must confirm on a card first. Only reading the clock does not: it changes nothing and shares nothing. */
    val requiresConfirmation: Boolean get() = true

    /** Opens the mail composer addressed to [to]; the user presses Send there. Nothing is ever sent silently. */
    data class SendEmail(val to: String, val subject: String, val body: String) : AgentAction

    /** Opens the calendar's own "new event" screen, filled. [end] is optional: the calendar picks its default length. */
    data class CreateCalendarEvent(
        val title: String,
        val start: LocalDateTime,
        val end: LocalDateTime?,
        val description: String,
    ) : AgentAction

    /**
     * A reminder notification at [at], through the app's own reminder scheduler; [documentId] is the letter it opens when tapped.
     * [offset] is what the model said when it gave an offset instead of a date ("in 2 hours"), so the card can show how the
     * request was understood; null for an absolute time.
     */
    data class ScheduleReminder(
        val at: LocalDateTime,
        val text: String,
        val documentId: String?,
        val offset: ReminderOffset? = null,
    ) : AgentAction

    /** The current date and time; answered at once, no card. */
    data object GetDateTime : AgentAction {
        override val requiresConfirmation: Boolean get() = false
    }
}

/**
 * The relative time a reminder's model stated, as it stated it: [days], [hours] and [minutes] from now, or, when [atTime] is true,
 * [days] from today at the stated time of day ("tomorrow at 9": days 1, at 09:00).
 */
data class ReminderOffset(val days: Int, val hours: Int, val minutes: Int, val atTime: Boolean)

/** The fields an action card shows (and the user may edit), across all action kinds. */
enum class ActionField {
    TO,
    SUBJECT,
    BODY,
    TITLE,
    START,
    END,
    DESCRIPTION,
    AT,
    TEXT,
}
