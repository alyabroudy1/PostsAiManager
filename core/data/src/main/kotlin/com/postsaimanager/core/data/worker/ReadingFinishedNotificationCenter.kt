package com.postsaimanager.core.data.worker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.postsaimanager.core.common.notification.NotificationIntents
import com.postsaimanager.core.common.notification.NotificationRoute
import com.postsaimanager.core.data.R
import com.postsaimanager.core.domain.reading.ReadingFinishedContent
import com.postsaimanager.core.domain.reading.ReadingFinishedNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the "Letter understood" notification on its own quiet channel ("Reading finished": low importance, no sound). One notification
 * at a time, updated in place as more letters finish ([com.postsaimanager.core.domain.reading.ReadingFinishedBatch] decides what is
 * in it).
 *
 * Private by construction: `VISIBILITY_PRIVATE`, with a public version that says only "A letter was understood" (or the count). What
 * the private version may say was already decided in the content ([ReadingFinishedContent]); this only renders it. A tap opens the
 * letter (or the list, for a group) through [NotificationIntents], so it passes the app lock like every notification of the app.
 * The permission is never asked for here: without it nothing is posted.
 */
@Singleton
class ReadingFinishedNotificationCenter @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReadingFinishedNotifier {

    override fun isShowing(): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching { manager.activeNotifications.any { it.id == NOTIFICATION_ID } }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission") // checked: areNotificationsEnabled() covers POST_NOTIFICATIONS
    override fun show(content: ReadingFinishedContent): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel(context)

        val rendered = ReadingFinishedRenderer.render(content, AndroidWords(context))
        val route = content.documentId?.let { NotificationRoute.Document(it) } ?: NotificationRoute.Documents
        val tap = NotificationIntents.contentIntent(context, route)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(rendered.title)
            .setContentText(rendered.text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(rendered.publicTitle)
                    .apply { rendered.publicText?.let { setContentText(it) } }
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(tap)
                    .build(),
            )
        if (rendered.lines.isNotEmpty()) {
            builder.setStyle(
                if (content.count == 1) {
                    NotificationCompat.BigTextStyle().bigText(rendered.lines.joinToString("\n"))
                } else {
                    NotificationCompat.InboxStyle().also { inbox -> rendered.lines.forEach(inbox::addLine) }
                },
            )
        }
        manager.notify(NOTIFICATION_ID, builder.build())
        return true
    }

    private class AndroidWords(private val context: Context) : ReadingFinishedWords {
        private val locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()

        override fun title() = context.getString(R.string.reading_finished_title)
        override fun groupTitle(count: Int) = context.resources.getQuantityString(R.plurals.reading_finished_group_title, count, count)
        override fun publicText() = context.getString(R.string.reading_finished_public)
        override fun due(date: LocalDate) = context.getString(R.string.reading_finished_due, dateText(date))
        override fun date(date: LocalDate) = dateText(date)
        override fun more(count: Int) = context.getString(R.string.reading_finished_more, count)
        override fun groupHidden() = context.getString(R.string.reading_finished_group_hidden)

        /** "15 Oct" in the device's language, with the year when it is not this year. */
        private fun dateText(date: LocalDate): String {
            val skeleton = if (date.year == LocalDate.now().year) "dMMM" else "dMMMy"
            return runCatching {
                DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
            }.getOrDefault(date.toString())
        }
    }

    companion object {
        const val CHANNEL_ID = "reading_finished"

        /** One notification for the whole batch, updated in place. */
        const val NOTIFICATION_ID = 0x5246

        /** Idempotent. Posting to an uncreated channel is not an option from API 26. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                // LOW: no sound by default. A letter being understood is news, not an alarm.
                NotificationChannel(CHANNEL_ID, context.getString(R.string.reading_finished_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.reading_finished_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }
}
