package com.postsaimanager.core.domain.timeline

import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.EventSource

/**
 * One kind of event on a profile's timeline, as data.
 *
 * @property id the stable key stored with every event.
 * @property description one English phrase saying what a letter of this kind does ("approves or grants what the reader asked for"), the
 *   content of the scoring question "Does this letter <description>?"; a model prompt, not UI text. Null for a kind the model never scores
 *   (the fallback "information", and the kinds code writes).
 * @property dateMeanings the date meanings ([com.postsaimanager.core.domain.extraction.v2.ValueMeaning] ids), best first, whose stored
 *   value is the date this kind happened on; the letter date, then the day the app read the letter, follow when none is stored. This is a
 *   mapping from the kind the model decided to which meaning the model already decided for each date, never a meaning rule of its own.
 * @property status where a matter stands after an event of this kind: open (an application filed, an objection that reopens a rejected
 *   matter), approved, rejected or closed; null when the kind says nothing about it (a payment demand, an appointment, information), so
 *   the earlier status stands.
 * @property source who writes events of this kind: [EventSource.DOCUMENT] kinds are scored by the model, the others by code.
 * @property labels the words for the timeline, by language code ("en", "de", "ar"); a language without an entry reads the English one.
 *   A new language is one entry per kind here.
 */
data class EventKind(
    val id: String,
    val description: String?,
    val dateMeanings: List<String>,
    val status: CaseStatus?,
    val source: EventSource,
    val labels: Map<String, String>,
) {
    /** Whether the model scores this kind against the baseline. */
    val scored: Boolean get() = description != null

    /** The label in [language] (a BCP-47 code; the region is ignored), else English. */
    fun label(language: String?): String {
        val code = language?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')
        return labels[code] ?: labels.getValue(ENGLISH)
    }

    companion object {
        const val ENGLISH = "en"
    }
}

/**
 * The event kinds of the timeline (plan 16, Part B), as a registry: the thirteen the plan names, and the three kinds of what the user
 * did through the app. A kind is one entry here (its question, its date meaning, what it does to a matter's status, its labels) and
 * nothing else; no code branches on an id except through these fields.
 *
 * Independent of the document's category and of its language: every letter is asked the same list.
 */
class EventKinds(val all: List<EventKind>) {

    init {
        require(all.map { it.id }.toSet().size == all.size) { "duplicate event kind id" }
        require(all.any { it.id == INFORMATION }) { "the registry needs the fallback kind" }
    }

    /** The kinds the model scores, in the order they are asked. */
    val scored: List<EventKind> = all.filter { it.scored }

    /** The kind with [id]; an id this build does not know is the fallback, [INFORMATION]. */
    fun byId(id: String?): EventKind = all.firstOrNull { it.id == id?.trim() } ?: all.first { it.id == INFORMATION }

    fun knows(id: String?): Boolean = all.any { it.id == id?.trim() }

    companion object {
        const val APPLICATION_FILED = "application_filed"
        const val APPROVAL = "approval"
        const val REJECTION = "rejection"
        const val DOCUMENTS_REQUESTED = "documents_requested"
        const val PAYMENT_DEMAND = "payment_demand"
        const val PAYMENT_REMINDER = "payment_reminder"
        const val MONEY_RECEIVED = "money_received"
        const val APPOINTMENT = "appointment"
        const val CANCELLATION = "cancellation"
        const val OBJECTION = "objection"
        const val CONTRACT_CHANGE = "contract_change"
        const val INFORMATION = "information"
        const val DEADLINE_PASSED = "deadline_passed"
        const val REMINDER_SET = "reminder_set"
        const val EMAIL_SENT = "email_sent"
        const val CALENDAR_ENTRY = "calendar_entry"

        // The ids of the value meanings (ValueMeanings.DEFAULT) the date mapping names.
        private const val LETTER_DATE = "LETTER_DATE"
        private const val APPOINTMENT_DATE = "APPOINTMENT"
        private const val VALID_FROM = "PERIOD_START"

        private fun doc(id: String, description: String, dates: List<String>, status: CaseStatus?, en: String, de: String, ar: String) =
            EventKind(id, description, dates, status, EventSource.DOCUMENT, mapOf("en" to en, "de" to de, "ar" to ar))

        private fun code(id: String, source: EventSource, en: String, de: String, ar: String) =
            EventKind(id, null, listOf(LETTER_DATE), null, source, mapOf("en" to en, "de" to de, "ar" to ar))

        val DEFAULT = EventKinds(
            listOf(
                doc(
                    APPLICATION_FILED, "confirms that the reader's application, request or claim was received or filed",
                    listOf(LETTER_DATE), CaseStatus.OPEN, "Application filed", "Antrag gestellt", "تقديم طلب",
                ),
                doc(
                    APPROVAL, "approves or grants what the reader applied for or asked for",
                    listOf(VALID_FROM, LETTER_DATE), CaseStatus.APPROVED, "Approval", "Bewilligung", "موافقة",
                ),
                doc(
                    REJECTION, "rejects or refuses what the reader applied for or asked for",
                    listOf(LETTER_DATE), CaseStatus.REJECTED, "Rejection", "Ablehnung", "رفض",
                ),
                doc(
                    DOCUMENTS_REQUESTED, "asks the reader to send in documents, proof or further information",
                    listOf(LETTER_DATE), null, "Documents requested", "Unterlagen angefordert", "طلب مستندات",
                ),
                doc(
                    PAYMENT_DEMAND, "demands a payment from the reader, such as an invoice or a bill",
                    listOf(LETTER_DATE), null, "Payment demand", "Zahlungsaufforderung", "مطالبة بالدفع",
                ),
                doc(
                    PAYMENT_REMINDER, "reminds the reader of a payment that is overdue",
                    listOf(LETTER_DATE), null, "Payment reminder", "Zahlungserinnerung", "تذكير بالدفع",
                ),
                doc(
                    MONEY_RECEIVED, "confirms that money was paid by the reader or credited or refunded to the reader",
                    listOf(LETTER_DATE), null, "Money received", "Geld eingegangen", "استلام مبلغ",
                ),
                doc(
                    APPOINTMENT, "invites the reader to an appointment or a meeting on a given date",
                    listOf(APPOINTMENT_DATE), null, "Appointment", "Termin", "موعد",
                ),
                doc(
                    CANCELLATION, "confirms that a contract, a membership or a service ends or was cancelled",
                    listOf(LETTER_DATE), CaseStatus.CLOSED, "Cancellation", "Kündigung", "إلغاء",
                ),
                doc(
                    OBJECTION, "is an objection, an appeal or a complaint against a decision",
                    listOf(LETTER_DATE), CaseStatus.OPEN, "Objection", "Widerspruch", "اعتراض",
                ),
                doc(
                    CONTRACT_CHANGE, "announces a change to the reader's contract, conditions, price or benefits",
                    listOf(VALID_FROM, LETTER_DATE), null, "Contract change", "Vertragsänderung", "تغيير في العقد",
                ),
                // The fallback: nothing scored passed the margin. Never scored, says nothing about where a matter stands.
                code(INFORMATION, EventSource.DOCUMENT, "Information", "Information", "معلومة"),
                // What code writes (no model): a due date passed with nothing done, and what the user did through the app.
                code(DEADLINE_PASSED, EventSource.SYSTEM, "Deadline passed", "Frist abgelaufen", "انتهاء المهلة"),
                code(REMINDER_SET, EventSource.ACTION, "Reminder set", "Erinnerung gesetzt", "تم ضبط تذكير"),
                code(EMAIL_SENT, EventSource.ACTION, "Email prepared", "E-Mail vorbereitet", "تم تجهيز بريد إلكتروني"),
                code(CALENDAR_ENTRY, EventSource.ACTION, "Calendar entry", "Kalendereintrag", "إدخال في التقويم"),
            ),
        )
    }
}
