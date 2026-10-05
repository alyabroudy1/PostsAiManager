package com.postsaimanager.core.domain.setup

import com.postsaimanager.core.model.DownloadSummary
import com.postsaimanager.core.model.ModelBannerState
import com.postsaimanager.core.model.SetupNeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/** The AI model downloads running (or failed) in the background; null when there are none. Implemented over the one download notification. */
interface DownloadActivity {
    val summary: Flow<DownloadSummary?>
}

/** What Home's model banner shows: the download progress while models download, the error when one failed, else the install prompt. */
class ObserveModelBannerUseCase @Inject constructor(
    private val observeSetupNeed: ObserveSetupNeedUseCase,
    private val downloads: DownloadActivity,
) {
    operator fun invoke(): Flow<ModelBannerState> =
        combine(observeSetupNeed(), downloads.summary) { need, summary -> bannerOf(need, summary) }.distinctUntilChanged()

    companion object {
        /** Pure, so every combination is testable. A running or failed download outranks the install prompt. */
        fun bannerOf(need: SetupNeed, summary: DownloadSummary?): ModelBannerState = when {
            summary != null && summary.failed -> ModelBannerState.Failed(summary)
            summary != null -> ModelBannerState.Downloading(summary)
            need == SetupNeed.SKIPPED -> ModelBannerState.Install
            else -> ModelBannerState.Hidden
        }
    }
}

/** For callers (and tests) with no downloads. */
object NoDownloadActivity : DownloadActivity {
    override val summary: Flow<DownloadSummary?> = flowOf(null)
}
