package com.postsaimanager.core.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * The notification channel every document-processing worker shares.
 *
 * Mirrors `core.download.DownloadNotifications`: the channel id and its creation live
 * together so anyone who can name the channel has already created it — posting to an
 * uncreated channel from API 26 onward is `CannotPostForegroundServiceNotificationException`,
 * a crash, not a degraded notification.
 */
object DocumentProcessingNotifications {

    const val CHANNEL_ID = "document_processing"

    /** Idempotent, and cheap enough to call before every post. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Document processing",
                // LOW: reading and understanding a document should be visible while it
                // runs, not intrusive — the same reasoning as model downloads.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress for documents being read and understood in the background."
                setShowBadge(false)
            },
        )
    }
}
