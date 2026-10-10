package com.postsaimanager.core.designsystem.component

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.designsystem.R

/**
 * The text for an error kind, in the app language. The domain keeps its errors Android-free; this
 * maps each kind to a string resource at the UI edge. Technical details stay out of the text.
 */
fun PamError.localizedMessage(context: Context): String = when (this) {
    is PamError.NetworkUnavailable -> context.getString(R.string.err_network_unavailable)
    is PamError.NetworkTimeout -> context.getString(R.string.err_network_timeout)
    is PamError.ServerError -> context.getString(R.string.err_server, statusCode)
    is PamError.DatabaseError -> context.getString(R.string.err_database)
    is PamError.FileNotFound -> context.getString(R.string.err_file_not_found)
    is PamError.StorageFull -> context.getString(R.string.err_storage_full)
    is PamError.FileWriteError -> context.getString(R.string.err_file_write)
    is PamError.ModelNotLoaded -> context.getString(R.string.err_model_not_loaded, modelName)
    is PamError.ModelLoadFailed -> context.getString(R.string.err_model_load_failed, modelName)
    is PamError.InferenceError -> context.getString(R.string.err_inference)
    is PamError.InsufficientMemory -> context.getString(R.string.err_insufficient_memory, requiredMb, availableMb)
    is PamError.OcrFailed -> context.getString(R.string.err_ocr_failed)
    is PamError.ExtractionFailed -> context.getString(R.string.err_extraction_failed)
    is PamError.ScannerUnavailable -> context.getString(R.string.err_scanner_unavailable)
    is PamError.ScanCancelled -> context.getString(R.string.err_scan_cancelled)
    is PamError.DownloadFailed -> context.getString(R.string.err_download_failed, modelName)
    is PamError.IntegrityCheckFailed -> context.getString(R.string.err_integrity_failed, modelName)
    is PamError.ValidationError -> context.getString(R.string.err_validation)
    is PamError.Unknown -> context.getString(R.string.err_unknown)
}

@Composable
fun PamError.localizedMessage(): String = localizedMessage(LocalContext.current)
