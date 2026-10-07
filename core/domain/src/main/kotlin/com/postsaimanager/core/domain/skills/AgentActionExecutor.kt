package com.postsaimanager.core.domain.skills

import java.time.LocalDateTime

/** What running an action gave. */
sealed interface ActionResult {
    /** Done. [detail] is what the caller may tell the model (the date and time for [AgentAction.GetDateTime]), else null. */
    data class Succeeded(val detail: String? = null) : ActionResult

    /** Could not be done (no app to open, notifications refused ...); [reason] is for the model and a log. */
    data class Failed(val reason: String) : ActionResult
}

/**
 * The port that really does an [AgentAction], in the main process (it needs an Activity-capable context to open the mail and
 * calendar apps). Only [ConfirmActionUseCase] calls it, after the user pressed Open (or at once for an action that
 * [AgentAction.requiresConfirmation] says does not need one): nothing else may reach it, which is how "nothing fires without Open"
 * holds.
 */
interface AgentActionExecutor {
    suspend fun execute(action: AgentAction): ActionResult
}

/**
 * The app's one way to remind the user at a time: a notification that opens the letter when tapped. The deadline reminders of the
 * app and the reminders a skill proposes both go through it, so there is one owner of "notify me later".
 */
interface ReminderScheduler {

    /** Schedules [text] for [at] (device time zone); [documentId] is the letter a tap opens. False when it cannot be scheduled. */
    suspend fun schedule(at: LocalDateTime, text: String, documentId: String?): Boolean

    /**
     * Schedules the deadline reminder of the letter [documentId] for [at]; the wording is the scheduler's (a string resource,
     * naming [sender] when known). One per letter: scheduling again replaces the earlier one. False when it cannot be scheduled.
     */
    suspend fun scheduleDeadline(at: LocalDateTime, documentId: String, sender: String?): Boolean

    /** Cancels every deadline reminder; reminders a skill proposed are left alone. */
    suspend fun cancelDeadlines()
}
