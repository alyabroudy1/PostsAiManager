package com.postsaimanager.core.data.skills

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.postsaimanager.core.data.R
import com.postsaimanager.core.data.worker.ReminderWorker
import com.postsaimanager.core.domain.skills.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's reminder scheduler: one WorkManager job per reminder, started after the delay to its time, that survives a restart of the
 * app and of the phone. WorkManager may run it a little late in Doze (minutes, not hours); a reminder is not an alarm clock.
 */
@Singleton
class WorkManagerReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderScheduler {

    override suspend fun schedule(at: LocalDateTime, text: String, documentId: String?): Boolean =
        enqueue(at, text, documentId, tags = listOf(TAG), uniqueName = null)

    override suspend fun scheduleDeadline(at: LocalDateTime, documentId: String, sender: String?): Boolean {
        val text = sender?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.reminder_deadline_with_sender, it) }
            ?: context.getString(R.string.reminder_deadline_without_sender)
        return enqueue(at, text, documentId, tags = listOf(TAG, DEADLINE_TAG), uniqueName = DEADLINE_PREFIX + documentId)
    }

    override suspend fun cancelDeadlines() {
        runCatching { WorkManager.getInstance(context).cancelAllWorkByTag(DEADLINE_TAG) }
    }

    /** One WorkManager job; with a [uniqueName] a job of that name is replaced, so scheduling the same letter twice leaves one. */
    private fun enqueue(at: LocalDateTime, text: String, documentId: String?, tags: List<String>, uniqueName: String?): Boolean {
        val delay = Duration.between(Instant.now(), at.atZone(ZoneId.systemDefault()).toInstant())
        if (delay.isNegative || delay.isZero || text.isBlank()) return false
        val input = Data.Builder()
            .putString(ReminderWorker.KEY_TEXT, text)
            .apply { documentId?.let { putString(ReminderWorker.KEY_DOCUMENT_ID, it) } }
            .build()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay).setInputData(input)
            .apply { tags.forEach(::addTag) }
            .build()
        val manager = WorkManager.getInstance(context)
        return runCatching {
            if (uniqueName == null) manager.enqueue(request) else manager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
        }.isSuccess
    }

    private companion object {
        const val TAG = "reminder"
        const val DEADLINE_TAG = "deadline_reminder"
        const val DEADLINE_PREFIX = "deadline-reminder-"
    }
}
