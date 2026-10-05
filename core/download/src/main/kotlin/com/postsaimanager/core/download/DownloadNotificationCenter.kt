package com.postsaimanager.core.download

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one owner of the download notification: every worker reports into the [DownloadBoard] here, and ONE notification (one id, one
 * channel) renders the whole board, so whichever worker posts shows all of them. Workers hand [foregroundInfo] to `setForeground`; the
 * ids being equal, WorkManager's foreground service and our updates address the same notification.
 *
 * Updates are throttled to about one a second; a change of state (queued, started, done) always goes through. When nothing is active
 * any more the notification is cancelled and the board forgotten (the quieter choice: no "ready" notification).
 */
@Singleton
class DownloadNotificationCenter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _board = MutableStateFlow(DownloadBoard())

    /**
     * The same state the notification shows, for Home's banner. A board with only finished items is forgotten; one holding a failed
     * item is kept so the banner can say so, until the download is queued again or cancelled.
     */
    val board: StateFlow<DownloadBoard> = _board.asStateFlow()

    private val throttle = ProgressThrottle(UPDATE_INTERVAL_MS)

    fun queued(id: String, name: String, totalBytes: Long) = change(force = true) { it.queued(id, name, totalBytes) }

    fun progress(id: String, name: String, bytes: Long, totalBytes: Long) =
        change(force = false) { it.downloading(id, name, bytes, totalBytes) }

    /** The worker is stopping to retry later (or was stopped): back to waiting. */
    fun waiting(id: String) = change(force = true) { it.waiting(id) }

    fun done(id: String) = change(force = true) { it.done(id) }

    fun failed(id: String) = change(force = true) { it.failed(id) }

    /** The user cancelled it. */
    fun removed(id: String) = change(force = true) { it.removed(id) }

    /** The notification as it is now, for `setForeground`. */
    @Synchronized
    fun foregroundInfo(): ForegroundInfo {
        DownloadNotifications.ensureChannel(context)
        val notification = build(_board.value)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Required from API 29 and enforced from 34; must match the manifest's service type.
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    @Synchronized
    private fun change(force: Boolean, transform: (DownloadBoard) -> DownloadBoard) {
        val next = transform(_board.value)
        if (!next.isActive) {
            _board.value = if (next.hasFailed) next else DownloadBoard()
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        _board.value = next
        if (force || throttle.allow(SystemClock.elapsedRealtime())) post(next)
    }

    private fun post(board: DownloadBoard) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        DownloadNotifications.ensureChannel(context)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(board))
    }

    private fun build(board: DownloadBoard): android.app.Notification {
        val title = if (board.itemCount > 1) {
            context.getString(R.string.download_title_many, board.currentPosition, board.itemCount)
        } else {
            context.getString(R.string.download_title_one, board.items.firstOrNull()?.name ?: context.getString(R.string.download_fallback_model))
        }
        val lines = board.items.map { context.getString(R.string.download_line, it.name, stateText(it)) }
        val percent = board.overallPercent

        return NotificationCompat.Builder(context, DownloadNotifications.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull { it.isNotBlank() })
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) }.setBigContentTitle(title))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent())
            .apply { if (percent != null) setProgress(100, percent, false) else setProgress(0, 0, true) }
            .build()
    }

    private fun stateText(item: DownloadItem): String = when (item.state) {
        DownloadItemState.WAITING -> context.getString(R.string.download_state_waiting)
        DownloadItemState.PREPARING -> context.getString(R.string.download_state_preparing)
        DownloadItemState.DOWNLOADING -> item.percent?.let { context.getString(R.string.download_state_downloading, it) }
            ?: context.getString(R.string.download_state_downloading_unknown)
        DownloadItemState.DONE -> context.getString(R.string.download_state_done)
        DownloadItemState.FAILED -> context.getString(R.string.download_state_failed)
    }

    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        /** The one id every download worker uses for `setForeground`. */
        const val NOTIFICATION_ID = 4711
        private const val UPDATE_INTERVAL_MS = 1_000L
    }
}
