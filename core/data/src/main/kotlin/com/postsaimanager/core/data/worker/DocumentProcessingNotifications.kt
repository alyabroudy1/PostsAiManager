package com.postsaimanager.core.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import com.postsaimanager.core.data.R
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.ProcessingState

/**
 * The notification channel every document-processing worker shares.
 *
 * Mirrors `core.download.DownloadNotifications`: the channel id and its creation live
 * together so anyone who can name the channel has already created it — posting to an
 * uncreated channel from API 26 onward is `CannotPostForegroundServiceNotificationException`,
 * a crash, not a degraded notification.
 *
 * Every word the user reads comes from a string resource, so it follows the device language;
 * [progressText] picks the resource and [Text.resolve] renders it.
 */
object DocumentProcessingNotifications {

    const val CHANNEL_ID = "document_processing"

    /** A string resource and its arguments, resolved against a context when the notification is built. */
    data class Text(@StringRes val res: Int, val args: List<Any> = emptyList()) {
        fun resolve(context: Context): String = context.getString(res, *args.toTypedArray())
    }

    /** The notification's title, always — it names no document. */
    @StringRes
    val TITLE: Int = R.string.processing_notification_title

    /** Shown instead of any progress detail while the app lock is on. */
    val DISCREET_TEXT = Text(R.string.processing_notification_discreet)

    /**
     * The one line under [TITLE]. With the app lock on ([discreet]) it is a fixed sentence:
     * a notification is visible on the lock screen and in the shade to anyone holding the phone,
     * so it must not reveal how many pages a letter has or what stage of understanding it is at.
     * Never contains a document title, sender or extracted text either way.
     */
    fun progressText(state: ProcessingState.Running?, discreet: Boolean): Text = when {
        discreet -> DISCREET_TEXT
        state == null -> Text(R.string.processing_notification_starting)
        else -> when (state.stage) {
            ProcessingStage.CAPTURE -> Text(R.string.processing_notification_preparing)
            ProcessingStage.READ ->
                if (state.currentPage != null && state.totalPages != null) {
                    Text(R.string.processing_notification_reading_page, listOf(state.currentPage!!, state.totalPages!!))
                } else {
                    Text(R.string.processing_notification_reading)
                }
            ProcessingStage.UNDERSTAND -> Text(R.string.processing_notification_analysing)
            ProcessingStage.LINK -> Text(R.string.processing_notification_matching)
            ProcessingStage.INDEX -> Text(R.string.processing_notification_indexing)
        }
    }

    /** Idempotent, and cheap enough to call before every post. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.processing_channel_name),
                // LOW: reading and understanding a document should be visible while it
                // runs, not intrusive — the same reasoning as model downloads.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.processing_channel_description)
                setShowBadge(false)
            },
        )
    }
}
