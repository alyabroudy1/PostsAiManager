package com.postsaimanager.setup

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.ai.catalog.download.ModelDownloadStatus
import com.postsaimanager.core.ai.embed.install.InstallStatus
import com.postsaimanager.core.model.SetupPartStatus
import org.junit.jupiter.api.Test

/** How the two download machines (WorkManager statuses) read as first-run setup progress. */
class SetupStatusMappingTest {

    private val size = 1_000L

    @Test
    fun `an installed chat model is done whatever the job says`() {
        assertThat(chatPartStatus(true, ModelDownloadStatus.NotStarted, size)).isEqualTo(SetupPartStatus.Done)
        assertThat(chatPartStatus(true, ModelDownloadStatus.Failed("x"), size)).isEqualTo(SetupPartStatus.Done)
    }

    @Test
    fun `job states map to waiting, downloading, failed and not started`() {
        assertThat(chatPartStatus(false, ModelDownloadStatus.NotStarted, size)).isEqualTo(SetupPartStatus.NotStarted)
        assertThat(chatPartStatus(false, ModelDownloadStatus.Cancelled, size)).isEqualTo(SetupPartStatus.NotStarted)
        assertThat(chatPartStatus(false, ModelDownloadStatus.Queued, size)).isEqualTo(SetupPartStatus.Waiting)
        assertThat(chatPartStatus(false, ModelDownloadStatus.Failed(null), size)).isEqualTo(SetupPartStatus.Failed)
        assertThat(chatPartStatus(false, ModelDownloadStatus.Running(250, 1_000), size))
            .isEqualTo(SetupPartStatus.Downloading(250, 1_000))
    }

    @Test
    fun `an unknown total falls back to the catalog size`() {
        assertThat(chatPartStatus(false, ModelDownloadStatus.Running(10, null), size))
            .isEqualTo(SetupPartStatus.Downloading(10, size))
    }

    @Test
    fun `a finished job that is not registered yet is not done`() {
        assertThat(chatPartStatus(false, ModelDownloadStatus.Complete("/m.gguf"), size))
            .isEqualTo(SetupPartStatus.Downloading(size, size))
    }

    @Test
    fun `search model states`() {
        assertThat(searchPartStatus(InstallStatus.NotStarted)).isEqualTo(SetupPartStatus.NotStarted)
        assertThat(searchPartStatus(InstallStatus.Waiting)).isEqualTo(SetupPartStatus.Waiting)
        assertThat(searchPartStatus(InstallStatus.Running(5, 10))).isEqualTo(SetupPartStatus.Downloading(5, 10))
        assertThat(searchPartStatus(InstallStatus.Installed)).isEqualTo(SetupPartStatus.Done)
        assertThat(searchPartStatus(InstallStatus.Failed)).isEqualTo(SetupPartStatus.Failed)
    }
}
