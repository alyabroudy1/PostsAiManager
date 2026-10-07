package com.postsaimanager.core.domain.skills

import com.postsaimanager.core.domain.form.AnswerVerifiers
import com.postsaimanager.core.domain.form.Verification
import java.time.LocalDateTime

/** What building an action from the card's fields gave. */
sealed interface FormResult {
    data class Built(val action: AgentAction) : FormResult

    /** The fields that cannot be used, with why; the card shows each next to its field. */
    data class Invalid(val errors: Map<ActionField, InvalidReason>) : FormResult
}

/**
 * The one mapping between an action and the text fields of its card: the card shows [entries] and lets the user edit them;
 * [build] reads the (possibly edited) texts back into an action, or says which field is not usable. Dates are `yyyy-MM-dd HH:mm`
 * ([ActionDateTime]).
 */
object ActionForm {

    /** The fields of [action] in the order the card shows them, with their text. */
    fun entries(action: AgentAction): List<Pair<ActionField, String>> = when (action) {
        is AgentAction.SendEmail -> listOf(ActionField.TO to action.to, ActionField.SUBJECT to action.subject, ActionField.BODY to action.body)
        is AgentAction.CreateCalendarEvent -> listOf(
            ActionField.TITLE to action.title,
            ActionField.START to ActionDateTime.format(action.start),
            ActionField.END to action.end?.let(ActionDateTime::format).orEmpty(),
            ActionField.DESCRIPTION to action.description,
        )
        is AgentAction.ScheduleReminder -> listOf(ActionField.AT to ActionDateTime.format(action.at), ActionField.TEXT to action.text)
        AgentAction.GetDateTime -> emptyList()
    }

    /**
     * The action [template] with the fields in [values] replacing its own (a field not in [values] keeps the template's text).
     * [now] decides whether a reminder is still in the future; with [requireFuture] false a time in the past is accepted (a card
     * being rebuilt is checked, and flagged, rather than refused).
     */
    fun build(template: AgentAction, values: Map<ActionField, String>, now: LocalDateTime, requireFuture: Boolean = true): FormResult {
        val text = entries(template).toMap() + values
        fun get(field: ActionField): String = text[field].orEmpty().trim()
        val errors = LinkedHashMap<ActionField, InvalidReason>()
        fun required(field: ActionField): String = get(field).also { if (it.isEmpty()) errors[field] = InvalidReason.REQUIRED }
        fun time(field: ActionField): LocalDateTime? {
            val raw = required(field)
            if (raw.isEmpty()) return null
            return ActionDateTime.parse(raw).also { if (it == null) errors[field] = InvalidReason.NOT_A_DATE_TIME }
        }

        val action: AgentAction? = when (template) {
            is AgentAction.SendEmail -> {
                val to = required(ActionField.TO)
                if (to.isNotEmpty() && AnswerVerifiers.verifyEmail(to) !is Verification.Accepted) errors[ActionField.TO] = InvalidReason.NOT_AN_EMAIL
                AgentAction.SendEmail(to = to, subject = get(ActionField.SUBJECT), body = text[ActionField.BODY].orEmpty())
            }
            is AgentAction.CreateCalendarEvent -> {
                val title = required(ActionField.TITLE)
                val start = time(ActionField.START)
                val end = if (get(ActionField.END).isEmpty()) null else ActionDateTime.parse(get(ActionField.END)).also {
                    if (it == null) errors[ActionField.END] = InvalidReason.NOT_A_DATE_TIME
                }
                if (start != null && end != null && end.isBefore(start)) errors[ActionField.END] = InvalidReason.END_BEFORE_START
                start?.let { AgentAction.CreateCalendarEvent(title = title, start = it, end = end, description = text[ActionField.DESCRIPTION].orEmpty()) }
            }
            is AgentAction.ScheduleReminder -> {
                val at = time(ActionField.AT)
                val message = required(ActionField.TEXT)
                if (requireFuture && at != null && !at.isAfter(now)) errors[ActionField.AT] = InvalidReason.IN_THE_PAST
                // What the model said ("in 2 hours") describes the time only while the user has not changed it.
                at?.let { AgentAction.ScheduleReminder(at = it, text = message, documentId = template.documentId, offset = if (it == template.at) template.offset else null) }
            }
            AgentAction.GetDateTime -> AgentAction.GetDateTime
        }
        return if (errors.isEmpty() && action != null) FormResult.Built(action) else FormResult.Invalid(errors)
    }
}
