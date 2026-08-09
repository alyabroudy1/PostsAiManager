package com.postsaimanager.core.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * The notification channel every download shares.
 *
 * Exists because sharing a channel **id** is not the same as sharing a channel. An earlier
 * version had the id as a public constant on one worker and the creation as a private
 * method on that same worker, so a second worker could reuse the id and post to a channel
 * that had never been created. From API 26 that is an invalid notification, and a
 * foreground service posting one is killed outright:
 *
 * ```
 * CannotPostForegroundServiceNotificationException: Bad notification for startForeground
 * ```
 *
 * Not a degraded notification — a crash, at the moment the download starts. Keeping the id
 * and its channel in one object means anyone who can name the channel has already created
 * it.
 *
 * One channel for all model downloads, deliberately: to the user these are one activity —
 * "the app is fetching something large" — and separate channels would mean separate
 * toggles in system settings to quiet the same thing.
 */
object DownloadNotifications {

    const val CHANNEL_ID = "model_downloads"

    /** Idempotent, and cheap enough to call before every post. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Model downloads",
                // LOW: an ongoing transfer should be visible, not intrusive.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress for AI models downloading in the background."
                setShowBadge(false)
            },
        )
    }
}
