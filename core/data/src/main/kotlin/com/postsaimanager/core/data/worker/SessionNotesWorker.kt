package com.postsaimanager.core.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.postsaimanager.core.domain.memory.SessionNotesCollector
import com.postsaimanager.core.domain.memory.SessionNotesQueue
import com.postsaimanager.core.domain.memory.SessionNotesRun
import com.postsaimanager.core.domain.usecase.ChatSessionEnd
import com.postsaimanager.core.domain.usecase.ChatSessionEnded
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the notes of one ended chat session ([SessionNotesCollector.write]) when the chat model is idle. Quiet, low-priority work
 * with no notification: it is queued the moment a session ends ([WorkManagerSessionNotesQueue]) and, when the model is not free (a
 * reading, a chat that is active again, no model resident), asks to be run again after [BACKOFF_SECONDS], up to [MAX_ATTEMPTS]
 * runs. It is unique per session ([SessionNotesWork.workName]), so a session queued twice is written once.
 */
@HiltWorker
class SessionNotesWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val collector: SessionNotesCollector,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val event = SessionNotesWork.eventOf(inputData) ?: return Result.failure()
        return when (collector.write(event)) {
            is SessionNotesRun.Done -> Result.success()
            SessionNotesRun.Later -> if (SessionNotesWork.shouldRetry(runAttemptCount)) Result.retry() else Result.success()
        }
    }
}

/** The data and the names of the queued notes work; pure, so the idempotence and the retry limit are unit-tested. */
internal object SessionNotesWork {

    const val KEY_CONVERSATION_ID = "conversationId"
    const val KEY_STARTED_AT = "startedAt"
    const val KEY_REASON = "reason"

    /** The wait before a deferred session is tried again. */
    const val BACKOFF_SECONDS = 60L

    /** The most runs of one session's notes: a model that is never free in that time (about an hour) leaves them unwritten. */
    const val MAX_ATTEMPTS = 12

    /** One work per session: its conversation and the time it began. */
    fun workName(event: ChatSessionEnded) = "session-notes-${event.conversationId}-${event.startedAt}"

    fun inputOf(event: ChatSessionEnded): Data = Data.Builder()
        .putString(KEY_CONVERSATION_ID, event.conversationId)
        .putLong(KEY_STARTED_AT, event.startedAt)
        .putString(KEY_REASON, event.reason.name)
        .build()

    fun eventOf(data: Data): ChatSessionEnded? {
        val conversationId = data.getString(KEY_CONVERSATION_ID) ?: return null
        return ChatSessionEnded(
            conversationId = conversationId,
            reason = data.getString(KEY_REASON)?.let { name -> ChatSessionEnd.entries.firstOrNull { it.name == name } } ?: ChatSessionEnd.LEFT,
            startedAt = data.getLong(KEY_STARTED_AT, 0L),
        )
    }

    /** True while [runAttemptCount] (0 on the first run) is below the limit. */
    fun shouldRetry(runAttemptCount: Int): Boolean = runAttemptCount < MAX_ATTEMPTS

    fun request(event: ChatSessionEnded): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SessionNotesWorker>()
            .setInputData(inputOf(event))
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
}

/** [SessionNotesQueue] over WorkManager: one unique work per ended session, kept when queued again. */
@Singleton
class WorkManagerSessionNotesQueue @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionNotesQueue {

    override fun enqueue(event: ChatSessionEnded) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SessionNotesWork.workName(event), ExistingWorkPolicy.KEEP, SessionNotesWork.request(event))
    }
}
