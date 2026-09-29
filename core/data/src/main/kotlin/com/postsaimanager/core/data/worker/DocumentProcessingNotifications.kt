package com.postsaimanager.core.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.ProcessingState

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

    /** The notification's title, always — it names no document. */
    const val TITLE = "Reading your document…"

    /** Shown instead of any progress detail while the app lock is on. */
    const val DISCREET_TEXT = "Working in the background"

    /**
     * The one line under [TITLE]. With the app lock on ([discreet]) it is a fixed sentence:
     * a notification is visible on the lock screen and in the shade to anyone holding the phone,
     * so it must not reveal how many pages a letter has or what stage of understanding it is at.
     * Never contains a document title, sender or extracted text either way.
     */
    fun progressText(state: ProcessingState.Running?, discreet: Boolean): String = when {
        discreet -> DISCREET_TEXT
        state == null -> "Starting…"
        else -> when (state.stage) {
            ProcessingStage.CAPTURE -> "Preparing…"
            ProcessingStage.READ ->
                if (state.currentPage != null && state.totalPages != null) {
                    "Reading page ${state.currentPage} of ${state.totalPages}"
                } else {
                    "Reading…"
                }
            ProcessingStage.UNDERSTAND -> "Analysing…"
            ProcessingStage.LINK -> "Matching profiles…"
            ProcessingStage.INDEX -> "Indexing for search…"
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
