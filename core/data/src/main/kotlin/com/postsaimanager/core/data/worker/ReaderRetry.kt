package com.postsaimanager.core.data.worker

import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequest
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.data.repository.READER_MODEL_NAME
import java.util.concurrent.TimeUnit

/**
 * When a reading is asked again instead of completed or failed: the reader model was unavailable (its session was lost, the chat model
 * replaced it, or a chat was active), so the reading is owed. The pipeline reports it as one error ([isReaderUnavailable]); the
 * reading workers answer it with `Result.retry()`, which WorkManager runs again after [BACKOFF_SECONDS] (the same quiet pattern as the
 * second stage's worker, which also asks to be run again while a chat is active). Scheduling only: it never looks at what a letter says.
 */
internal object ReaderRetry {

    /** The wait before a reading is run again. */
    const val BACKOFF_SECONDS = 60L

    /** The most runs of one work before it gives up (the pipeline bounds a first reading itself; this covers a quiet re-read). */
    const val MAX_ATTEMPTS = 8

    /** True for the error the pipeline returns when a reader model is installed but did not read. */
    fun isReaderUnavailable(error: PamError): Boolean = error is PamError.ModelNotLoaded && error.modelName == READER_MODEL_NAME

    /** True when [error] asks for another run and [runAttemptCount] (0 on the first run) is still below the limit. */
    fun shouldRetry(error: PamError, runAttemptCount: Int): Boolean = isReaderUnavailable(error) && runAttemptCount < MAX_ATTEMPTS

    /** [builder]'s work, run again after [BACKOFF_SECONDS] each time it asks for a retry. */
    fun backoff(builder: OneTimeWorkRequest.Builder): OneTimeWorkRequest.Builder =
        builder.setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
}
