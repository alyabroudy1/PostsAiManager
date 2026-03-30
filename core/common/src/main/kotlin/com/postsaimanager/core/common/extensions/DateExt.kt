package com.postsaimanager.core.common.extensions

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats a timestamp (millis) into a human-readable date string.
 */
fun Long.toFormattedDate(pattern: String = "dd.MM.yyyy", locale: Locale = Locale.GERMANY): String {
    return SimpleDateFormat(pattern, locale).format(Date(this))
}

/**
 * Formats a timestamp into a relative time string (e.g., "2 hours ago").
 */
fun Long.toRelativeTime(): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> toFormattedDate()
    }
}
