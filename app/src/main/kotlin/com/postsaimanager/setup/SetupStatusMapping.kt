package com.postsaimanager.setup

import com.postsaimanager.core.ai.catalog.download.ModelDownloadStatus
import com.postsaimanager.core.ai.embed.install.InstallStatus
import com.postsaimanager.core.model.SetupPartStatus

/**
 * The chat model's setup state from the two things that know about it: the installed index ([installed], the authority, as for the
 * search model) and the download job. A finished job whose file is not registered yet reads as "all bytes downloaded", never "done".
 */
internal fun chatPartStatus(installed: Boolean, download: ModelDownloadStatus, sizeBytes: Long): SetupPartStatus = when {
    installed -> SetupPartStatus.Done
    else -> when (download) {
        ModelDownloadStatus.NotStarted, ModelDownloadStatus.Cancelled -> SetupPartStatus.NotStarted
        ModelDownloadStatus.Queued -> SetupPartStatus.Waiting
        is ModelDownloadStatus.Running -> SetupPartStatus.Downloading(download.bytesDownloaded, download.totalBytes ?: sizeBytes)
        is ModelDownloadStatus.Failed -> SetupPartStatus.Failed
        is ModelDownloadStatus.Complete -> SetupPartStatus.Downloading(sizeBytes, sizeBytes)
    }
}

internal fun searchPartStatus(status: InstallStatus): SetupPartStatus = when (status) {
    InstallStatus.NotStarted -> SetupPartStatus.NotStarted
    InstallStatus.Waiting -> SetupPartStatus.Waiting
    is InstallStatus.Running -> SetupPartStatus.Downloading(status.bytesDownloaded, status.totalBytes)
    InstallStatus.Installed -> SetupPartStatus.Done
    InstallStatus.Failed -> SetupPartStatus.Failed
}
