package com.postsaimanager.core.download

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [ResumePolicy].
 *
 * Resumability is a correctness requirement here, not a nicety: models are 0.4–2 GB and
 * downloads happen over mobile networks. A download that restarts from zero after a tunnel
 * is a broken product, and one that appends after a `200` response silently produces a
 * corrupt file that only fails much later at the SHA-256 check.
 */
class ResumePolicyTest {

    @Nested
    @DisplayName("Pre-request decision")
    inner class Decide {

        @Test
        fun `no partial file starts fresh`() {
            assertThat(ResumePolicy.decide(existingBytes = 0, expectedTotal = 1_000))
                .isEqualTo(ResumePolicy.Decision.StartFresh)
        }

        @Test
        fun `partial file resumes from its length`() {
            assertThat(ResumePolicy.decide(existingBytes = 400, expectedTotal = 1_000))
                .isEqualTo(ResumePolicy.Decision.Resume(400))
        }

        @Test
        fun `complete file skips to verification`() {
            assertThat(ResumePolicy.decide(existingBytes = 1_000, expectedTotal = 1_000))
                .isEqualTo(ResumePolicy.Decision.AlreadyComplete)
        }

        @Test
        @DisplayName("an over-long partial is discarded, not resumed")
        fun `partial larger than expected starts fresh`() {
            // Means the artefact changed, or a previous append-after-200 bug ran.
            // Resuming would append onto already-corrupt bytes.
            assertThat(ResumePolicy.decide(existingBytes = 1_500, expectedTotal = 1_000))
                .isEqualTo(ResumePolicy.Decision.StartFresh)
        }

        @Test
        fun `unknown total still resumes`() {
            assertThat(ResumePolicy.decide(existingBytes = 400, expectedTotal = null))
                .isEqualTo(ResumePolicy.Decision.Resume(400))
        }

        @Test
        fun `negative length is treated as absent`() {
            assertThat(ResumePolicy.decide(existingBytes = -1, expectedTotal = 1_000))
                .isEqualTo(ResumePolicy.Decision.StartFresh)
        }
    }

    @Nested
    @DisplayName("Write mode — the corruption-critical decision")
    inner class WriteMode {

        @Test
        fun `206 after a range request appends`() {
            assertThat(ResumePolicy.writeModeFor(206, requestedOffset = 400))
                .isEqualTo(ResumePolicy.WriteMode.APPEND)
        }

        @Test
        @DisplayName("200 after a range request TRUNCATES — appending here is the corruption bug")
        fun `200 after a range request truncates`() {
            // Servers without range support answer a Range request with the full body and
            // 200 OK. Appending that to existing bytes yields a file of roughly double the
            // expected size that passes every check until SHA-256 fails at the very end.
            assertThat(ResumePolicy.writeModeFor(200, requestedOffset = 400))
                .isEqualTo(ResumePolicy.WriteMode.TRUNCATE)
        }

        @Test
        fun `a fresh download always truncates`() {
            assertThat(ResumePolicy.writeModeFor(200, requestedOffset = 0))
                .isEqualTo(ResumePolicy.WriteMode.TRUNCATE)
            assertThat(ResumePolicy.writeModeFor(206, requestedOffset = 0))
                .isEqualTo(ResumePolicy.WriteMode.TRUNCATE)
        }
    }

    @Nested
    @DisplayName("Status handling")
    inner class Status {

        @Test
        fun `200 and 206 are success`() {
            assertThat(ResumePolicy.isSuccess(200)).isTrue()
            assertThat(ResumePolicy.isSuccess(206)).isTrue()
        }

        @Test
        fun `errors are not success`() {
            listOf(301, 403, 404, 416, 500, 503).forEach {
                assertThat(ResumePolicy.isSuccess(it)).isFalse()
            }
        }

        @Test
        @DisplayName("416 triggers a restart rather than surfacing an error")
        fun `range not satisfiable restarts`() {
            // The partial file is stale relative to the server's entity. Recoverable
            // automatically — the user should never see this.
            assertThat(ResumePolicy.shouldRestartAfter(416)).isTrue()
            assertThat(ResumePolicy.shouldRestartAfter(500)).isFalse()
        }
    }
}
