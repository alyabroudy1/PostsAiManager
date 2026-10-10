package com.postsaimanager.core.common.extensions

import android.content.Context
import android.text.format.DateUtils
import com.postsaimanager.core.common.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats a timestamp (millis) into a date string: the locale's own short date ("10.10.2026", "10/10/2026", Arabic digits where the
 * locale uses them), or [pattern] when one is given.
 */
fun Long.toFormattedDate(pattern: String? = null, locale: Locale = Locale.getDefault()): String {
    val format = if (pattern == null) DateFormat.getDateInstance(DateFormat.SHORT, locale) else SimpleDateFormat(pattern, locale)
    return format.format(Date(this))
}

/**
 * Formats a timestamp into a relative time string in the app's language ("2 hours ago", "vor 2 Std.", Arabic); a timestamp older than
 * a week is written by [older] (screens pass the app's friendly short date, see `FriendlyDate`).
 */
fun Long.toRelativeTime(context: Context, older: (Long) -> String = { it.toFormattedDate() }): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    return when {
        diff < DateUtils.MINUTE_IN_MILLIS -> context.getString(R.string.common_just_now)
        diff < DateUtils.WEEK_IN_MILLIS ->
            DateUtils.getRelativeTimeSpanString(this, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        else -> older(this)
    }
}
