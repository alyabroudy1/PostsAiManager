package com.postsaimanager.core.domain.setup

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.DownloadSummary
import com.postsaimanager.core.model.ModelBannerState
import com.postsaimanager.core.model.SetupNeed
import org.junit.jupiter.api.Test

/** Home's model banner: progress while downloading, the error when a download failed, the install prompt after "Skip", else nothing. */
class ObserveModelBannerUseCaseTest {

    private val running = DownloadSummary(position = 1, count = 3, percent = 45)

    @Test
    fun `a running download shows the progress, also when the setup was postponed`() {
        assertThat(ObserveModelBannerUseCase.bannerOf(SetupNeed.SKIPPED, running)).isEqualTo(ModelBannerState.Downloading(running))
        assertThat(ObserveModelBannerUseCase.bannerOf(SetupNeed.NOT_NEEDED, running)).isEqualTo(ModelBannerState.Downloading(running))
    }

    @Test
    fun `a failed download shows the error with the way back`() {
        val failed = running.copy(failed = true)
        assertThat(ObserveModelBannerUseCase.bannerOf(SetupNeed.SKIPPED, failed)).isEqualTo(ModelBannerState.Failed(failed))
    }

    @Test
    fun `no download and a postponed setup shows the install prompt`() {
        assertThat(ObserveModelBannerUseCase.bannerOf(SetupNeed.SKIPPED, null)).isEqualTo(ModelBannerState.Install)
    }

    @Test
    fun `nothing to say shows no banner`() {
        assertThat(ObserveModelBannerUseCase.bannerOf(SetupNeed.NOT_NEEDED, null)).isEqualTo(ModelBannerState.Hidden)
        assertThat(ObserveModelBannerUseCase.bannerOf(SetupNeed.REQUIRED, null)).isEqualTo(ModelBannerState.Hidden)
    }
}
