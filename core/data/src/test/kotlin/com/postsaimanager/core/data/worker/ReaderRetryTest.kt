package com.postsaimanager.core.data.worker

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.data.repository.READER_MODEL_NAME
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ReaderRetryTest {

    private val unavailable = PamError.ModelNotLoaded(READER_MODEL_NAME)

    @Test
    @DisplayName("an unavailable reader asks for another run, up to the limit")
    fun retriesUntilTheLimit() {
        assertThat(ReaderRetry.shouldRetry(unavailable, runAttemptCount = 0)).isTrue()
        assertThat(ReaderRetry.shouldRetry(unavailable, runAttemptCount = ReaderRetry.MAX_ATTEMPTS - 1)).isTrue()
        assertThat(ReaderRetry.shouldRetry(unavailable, runAttemptCount = ReaderRetry.MAX_ATTEMPTS)).isFalse()
    }

    @Test
    @DisplayName("any other failure is not retried")
    fun otherErrorsAreNot() {
        assertThat(ReaderRetry.shouldRetry(PamError.ModelNotLoaded("chat"), 0)).isFalse()
        assertThat(ReaderRetry.shouldRetry(PamError.ExtractionFailed(detail = "x"), 0)).isFalse()
    }

    @Test
    @DisplayName("a reading is run again after a minute, one wait after another")
    fun backoffIsAMinute() {
        val request = ReaderRetry.backoff(androidx.work.OneTimeWorkRequestBuilder<ReprocessDocumentWorker>()).build()

        assertThat(request.workSpec.backoffPolicy).isEqualTo(androidx.work.BackoffPolicy.LINEAR)
        assertThat(request.workSpec.backoffDelayDuration).isEqualTo(60_000L)
    }
}
