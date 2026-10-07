package com.postsaimanager.core.data.worker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.postsaimanager.core.common.notification.NotificationIntents
import com.postsaimanager.core.common.notification.NotificationRoute
import com.postsaimanager.core.data.R
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Posts one reminder when its time comes (scheduled by [com.postsaimanager.core.data.skills.WorkManagerReminderScheduler]).
 *
 * A tap opens the letter ([NotificationRoute.Document]) through the app lock, like every notification of the app. With the app lock
 * on, the notification shows a fixed sentence instead of the reminder's text (the shade and the lock screen are visible to anyone
 * holding the phone), the same rule as the processing notification.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val userPreferences: UserPreferencesRepository,
) : CoroutineWorker(appContext, params) {

    @SuppressLint("MissingPermission") // checked: areNotificationsEnabled() covers POST_NOTIFICATIONS
    override suspend fun doWork(): Result {
        val text = inputData.getString(KEY_TEXT)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val documentId = inputData.getString(KEY_DOCUMENT_ID)
        val manager = NotificationManagerCompat.from(applicationContext)
        // Refused notifications: nothing to show, and nothing to retry.
        if (!manager.areNotificationsEnabled()) return Result.success()
        ensureChannel(applicationContext)

        val discreet = runCatching { userPreferences.getUserPreferences().first().biometricEnabled }.getOrDefault(false)
        val title = applicationContext.getString(R.string.reminder_notification_title)
        val route = documentId?.let { NotificationRoute.Document(it) } ?: NotificationRoute.Documents
        val tap = NotificationIntents.contentIntent(applicationContext, route)
        val generic = applicationContext.getString(R.string.reminder_notification_discreet)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (discreet) generic else text)
            .setStyle(if (discreet) null else NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(generic)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentIntent(tap)
                    .build(),
            )
            .build()
        manager.notify(id.hashCode(), notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "deadline_reminders"
        const val KEY_TEXT = "text"
        const val KEY_DOCUMENT_ID = "document_id"

        /** Idempotent. Posting to an uncreated channel is not an option from API 26. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.reminder_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.reminder_channel_description)
                },
            )
        }
    }
}
