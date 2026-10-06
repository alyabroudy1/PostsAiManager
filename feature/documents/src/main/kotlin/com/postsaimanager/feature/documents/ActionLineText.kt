package com.postsaimanager.feature.documents

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.postsaimanager.core.designsystem.component.FriendlyDate
import com.postsaimanager.core.domain.extraction.actions.ActionLine
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The templates of the action lines, the one place that knows them: string resources keyed by the kind's id and by the values a template
 * states (`a` the amount, `p` the party, `d` the date; always in that order, which is the order of the template's arguments). A line is
 * rendered with the most specific template of its kind that its values allow, so a missing value gives a shorter line. A kind this build
 * has no templates for is not shown.
 */
internal object ActionTemplates {

    private val kinds: Map<String, Map<String, Int>> = mapOf(
        "pay" to mapOf(
            "" to R.string.action_pay, "a" to R.string.action_pay_a, "p" to R.string.action_pay_p, "d" to R.string.action_pay_d,
            "ap" to R.string.action_pay_ap, "ad" to R.string.action_pay_ad, "pd" to R.string.action_pay_pd, "apd" to R.string.action_pay_apd,
        ),
        "reply" to mapOf("" to R.string.action_reply, "p" to R.string.action_reply_p, "d" to R.string.action_reply_d, "pd" to R.string.action_reply_pd),
        "object_cancel" to mapOf("" to R.string.action_object_cancel, "d" to R.string.action_object_cancel_d),
        "attend" to mapOf("" to R.string.action_attend, "d" to R.string.action_attend_d),
        "send_documents" to mapOf(
            "" to R.string.action_send_documents, "p" to R.string.action_send_documents_p, "d" to R.string.action_send_documents_d,
            "pd" to R.string.action_send_documents_pd,
        ),
        "sign_return" to mapOf("" to R.string.action_sign_return, "d" to R.string.action_sign_return_d),
        "confirm_renew" to mapOf("" to R.string.action_confirm_renew, "d" to R.string.action_confirm_renew_d),
        "contact" to mapOf("" to R.string.action_contact, "p" to R.string.action_contact_p, "d" to R.string.action_contact_d, "pd" to R.string.action_contact_pd),
    )

    /** A template and the values it states, in the order of its arguments. */
    class Picked(@StringRes val template: Int, val args: List<String>)

    /** The template of [kindId] for the values present, or null when this build has none for the kind. */
    fun pick(kindId: String, amount: String?, party: String?, date: String?): Picked? {
        val templates = kinds[kindId] ?: return null
        val values = listOf('a' to amount, 'p' to party, 'd' to date).filter { it.second != null }
        // Every combination of the values present, the one with the most values first; among equals the one that keeps the amount,
        // then the date, so the party is the first to be left out.
        val combinations = (0 until (1 shl values.size)).map { mask -> values.filterIndexed { i, _ -> mask and (1 shl i) != 0 } }
            .sortedWith(compareByDescending<List<Pair<Char, String?>>> { it.size }.thenByDescending { c -> c.sumOf { WEIGHT.getValue(it.first) } })
        for (combination in combinations) {
            val template = templates[combination.map { it.first }.joinToString("")] ?: continue
            return Picked(template, combination.map { it.second.orEmpty() })
        }
        return null
    }

    private val WEIGHT = mapOf('a' to 4, 'd' to 2, 'p' to 1)
}

/** The sentence of [line] in the app's language, or null when this build has no template for its kind. */
@Composable
internal fun actionLineText(line: ActionLine): String? {
    val locale = Locale.getDefault()
    val day = line.date?.let { actionDateText(it.date, locale) }
    val time = line.date?.time?.let { actionTimeText(it, locale) }
    val date = if (day != null && time != null) stringResource(R.string.action_date_time, day, time) else day
    val picked = ActionTemplates.pick(line.kind.id, line.amount, line.party, date) ?: return null
    return stringResource(picked.template, *picked.args.toTypedArray())
}

/**
 * The date of an action line ("5 Nov", "5. Nov. 2027"): the app's shared [FriendlyDate] formatter, so a date reads the same in the
 * list, the timeline and here. No formatting of its own.
 */
internal fun actionDateText(date: LocalDate, locale: Locale): String? =
    runCatching { FriendlyDate.format(date, date.year != LocalDate.now().year, locale) }.getOrNull()

/** The time of day as the locale writes it (24-hour or 12-hour as the device is set). */
internal fun actionTimeText(time: LocalTime, locale: Locale): String? =
    runCatching { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, TIME_SKELETON), locale).format(time) }.getOrNull()

private const val TIME_SKELETON = "jm"
