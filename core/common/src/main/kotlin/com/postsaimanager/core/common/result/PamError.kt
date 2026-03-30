package com.postsaimanager.core.common.result

/**
 * Sealed hierarchy of all error types in the app.
 * Every error carries a user-facing message for consistent error display.
 *
 * UI layers use [userMessage] to show errors in snackbars/dialogs.
 * Logging layers use [cause] for crash reporting.
 */
sealed class PamError(
    open val userMessage: String,
    open val cause: Throwable? = null,
) {
    // ── Network Errors ──
    data class NetworkUnavailable(
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "No internet connection. Please check your network.",
        cause = cause,
    )

    data class NetworkTimeout(
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Request timed out. Please try again.",
        cause = cause,
    )

    data class ServerError(
        val statusCode: Int,
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Server error ($statusCode). Please try again later.",
        cause = cause,
    )

    // ── Database Errors ──
    data class DatabaseError(
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Failed to access local storage.",
        cause = cause,
    )

    // ── File System Errors ──
    data class FileNotFound(
        val path: String,
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "File not found.",
        cause = cause,
    )

    data class StorageFull(
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Not enough storage space.",
        cause = cause,
    )

    data class FileWriteError(
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Failed to save file.",
        cause = cause,
    )

    // ── AI Engine Errors ──
    data class ModelNotLoaded(
        val modelName: String,
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "AI model \"$modelName\" is not loaded.",
        cause = cause,
    )

    data class ModelLoadFailed(
        val modelName: String,
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Failed to load AI model \"$modelName\".",
        cause = cause,
    )

    data class InferenceError(
        val detail: String,
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "AI processing failed: $detail",
        cause = cause,
    )

    data class InsufficientMemory(
        val requiredMb: Long,
        val availableMb: Long,
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Not enough memory. Required: ${requiredMb}MB, Available: ${availableMb}MB.",
        cause = cause,
    )

    // ── Extraction Errors ──
    data class OcrFailed(
        val detail: String,
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Text recognition failed: $detail",
        cause = cause,
    )

    data class ExtractionFailed(
        val detail: String,
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Data extraction failed: $detail",
        cause = cause,
    )

    // ── Scanner Errors ──
    data class ScannerUnavailable(
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Document scanner is not available on this device.",
        cause = cause,
    )

    data class ScanCancelled(
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Scan was cancelled.",
        cause = cause,
    )

    // ── Download Errors ──
    data class DownloadFailed(
        val modelName: String,
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Failed to download \"$modelName\".",
        cause = cause,
    )

    data class IntegrityCheckFailed(
        val modelName: String,
        override val cause: Throwable? = null,
    ) : PamError(
        userMessage = "Downloaded file for \"$modelName\" is corrupted. Please try again.",
        cause = cause,
    )

    // ── Validation Errors ──
    data class ValidationError(
        val field: String,
        val reason: String,
    ) : PamError(
        userMessage = "$field: $reason",
    )

    // ── Generic / Unknown ──
    data class Unknown(
        override val userMessage: String = "An unexpected error occurred.",
        override val cause: Throwable? = null,
    ) : PamError(userMessage = userMessage, cause = cause)
}
