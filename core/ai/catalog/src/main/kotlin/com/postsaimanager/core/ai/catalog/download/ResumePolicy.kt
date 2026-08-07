package com.postsaimanager.core.ai.catalog.download

/**
 * Decides how to continue a partially-downloaded file.
 *
 * Kept as pure functions, separate from the HTTP code, because the two mistakes that ruin
 * resumable downloads are both decisions rather than I/O:
 *
 * 1. Appending to a partial file when the server ignored the `Range` header and sent the
 *    whole body again — silently producing a corrupt file twice the expected size.
 * 2. Restarting from zero on every interruption, which turns a multi-gigabyte download
 *    over mobile data into something that never completes.
 */
object ResumePolicy {

    /** What to do before issuing the request. */
    sealed interface Decision {
        /** Ask for `bytes=[fromByte]-`. */
        data class Resume(val fromByte: Long) : Decision

        /** No usable partial data; request the whole file. */
        data object StartFresh : Decision

        /** Bytes on disk already match the expected size; skip straight to verification. */
        data object AlreadyComplete : Decision
    }

    /** What to do with the bytes once the response headers are known. */
    enum class WriteMode {
        /** Server honoured the range (206). Continue after the existing bytes. */
        APPEND,

        /** Server sent the whole entity (200). Discard the partial file first. */
        TRUNCATE,
    }

    /**
     * @param existingBytes size of the partial file on disk, 0 if absent
     * @param expectedTotal total size from the catalog, null if unknown
     */
    fun decide(existingBytes: Long, expectedTotal: Long?): Decision = when {
        existingBytes <= 0L -> Decision.StartFresh

        // More bytes than the file should contain means the partial is not what we think
        // it is — a changed artefact, or a previous append-after-200 bug. Never trust it.
        expectedTotal != null && existingBytes > expectedTotal -> Decision.StartFresh

        expectedTotal != null && existingBytes == expectedTotal -> Decision.AlreadyComplete

        else -> Decision.Resume(existingBytes)
    }

    /**
     * Maps the response status to a write mode.
     *
     * A `Range` request may legitimately be answered with `200 OK` and the complete body —
     * many CDNs and every server without range support do exactly this. Appending in that
     * case is the corruption bug; the partial data must be discarded instead.
     */
    fun writeModeFor(statusCode: Int, requestedOffset: Long): WriteMode = when {
        requestedOffset <= 0L -> WriteMode.TRUNCATE
        statusCode == HTTP_PARTIAL_CONTENT -> WriteMode.APPEND
        else -> WriteMode.TRUNCATE
    }

    /** Whether a status means "keep going" rather than "fail". */
    fun isSuccess(statusCode: Int): Boolean =
        statusCode == HTTP_OK || statusCode == HTTP_PARTIAL_CONTENT

    /**
     * A range request past the end of the entity yields 416. The partial file is stale, so
     * the correct recovery is a fresh download rather than a failure the user must action.
     */
    fun shouldRestartAfter(statusCode: Int): Boolean =
        statusCode == HTTP_RANGE_NOT_SATISFIABLE

    const val HTTP_OK = 200
    const val HTTP_PARTIAL_CONTENT = 206
    const val HTTP_RANGE_NOT_SATISFIABLE = 416
}
