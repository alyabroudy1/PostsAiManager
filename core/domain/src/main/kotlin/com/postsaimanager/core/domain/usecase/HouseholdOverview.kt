package com.postsaimanager.core.domain.usecase

import java.time.LocalDate

/**
 * What the all-documents chat's card says about the household: who is in it, which matters are open, what is due soon and which
 * letters ask for something. Every value is something the app already decided (the household roles, the cases and their status, the
 * stored deadlines and action items); nothing here is decided again.
 *
 * @property people the household persons.
 * @property cases the open matters, most recently active first.
 * @property deadlines the deadlines in the coming days, soonest first.
 * @property actions the letters that ask the reader to do something.
 */
data class HouseholdOverview(
    val today: LocalDate,
    val people: List<Person> = emptyList(),
    val cases: List<OpenCase> = emptyList(),
    val deadlines: List<Deadline> = emptyList(),
    val actions: List<ActionLetter> = emptyList(),
) {
    /** [relation] is how the person relates to the user ("me", "child"); null when unknown. */
    data class Person(val name: String, val relation: String?)

    /** An open matter of [person]; [latest] is its newest event as one short phrase (date, kind, title), null when none is left to show. */
    data class OpenCase(val person: String, val organisation: String?, val title: String, val latest: String?)

    data class Deadline(val date: LocalDate, val person: String?, val sender: String?, val title: String)

    data class ActionLetter(val title: String, val sender: String?, val person: String?)

    val isEmpty: Boolean get() = people.isEmpty() && cases.isEmpty() && deadlines.isEmpty() && actions.isEmpty()
}

/**
 * The overview as the few lines the card carries, within [MAX_CHARS]. Pure.
 *
 * ```
 * ## Household overview (2026-10-07)
 * People: Maria (me), Omar (child)
 * Open cases:
 * - Maria: Jobcenter, Bürgergeld application. Latest: 2026-09-10 Approval, Bürgergeld approved from 1 Sep
 * Deadlines in the next 30 days:
 * - 2026-10-15 Maria: Stadtwerke, Jahresabrechnung
 * Letters needing action:
 * - Jahresabrechnung (Stadtwerke), for Maria
 * ```
 * The sections come in that order and each line is whole or absent: when the cap is reached the rest of that section, and the sections
 * after it, are dropped, so the household and the open matters are never cut for the sake of a later list.
 */
object HouseholdOverviewFormat {

    const val MAX_CHARS = 1200

    /** The deadline window, in days from today (today included). */
    const val DEADLINE_DAYS = 30L

    private const val MAX_PEOPLE = 8

    /** The text, starting with a blank line like every card section, or an empty string when there is nothing to say. */
    fun format(overview: HouseholdOverview, maxChars: Int = MAX_CHARS): String {
        if (overview.isEmpty) return ""
        val out = StringBuilder("\n## Household overview (${overview.today})\n")
        fun fits(line: String) = out.length + line.length + 1 <= maxChars

        // Once a line does not fit, nothing after it is added.
        var full = false
        fun section(header: String, lines: List<String>) {
            if (full || lines.isEmpty()) return
            if (!fits(header + "\n" + lines.first())) {
                full = true
                return
            }
            out.append(header).append('\n')
            for (line in lines) {
                if (!fits(line)) {
                    full = true
                    return
                }
                out.append(line).append('\n')
            }
        }

        if (overview.people.isNotEmpty()) {
            val names = overview.people.take(MAX_PEOPLE).map { p -> p.name + (p.relation?.let { " ($it)" } ?: "") }
            var line = "People: " + names.joinToString(", ")
            // A long list gives up names from the end until the line fits.
            var count = names.size
            while (!fits(line) && count > 1) {
                count--
                line = "People: " + names.take(count).joinToString(", ")
            }
            if (fits(line)) out.append(line).append('\n')
        }
        section(
            "Open cases:",
            overview.cases.map { c ->
                "- ${c.person}: " + listOfNotNull(c.organisation, c.title).joinToString(", ") + (c.latest?.let { ". Latest: $it" } ?: "")
            },
        )
        section(
            "Deadlines in the next $DEADLINE_DAYS days:",
            overview.deadlines.map { d ->
                "- ${d.date} " + (d.person?.let { "$it: " } ?: "") + listOfNotNull(d.sender, d.title).joinToString(", ")
            },
        )
        section(
            "Letters needing action:",
            overview.actions.map { a ->
                "- ${a.title}" + (a.sender?.let { " ($it)" } ?: "") + (a.person?.let { ", for $it" } ?: "")
            },
        )
        return out.toString()
    }
}
