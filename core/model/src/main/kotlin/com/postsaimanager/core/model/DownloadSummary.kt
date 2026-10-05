package com.postsaimanager.core.model

/**
 * The AI model downloads together, as Home shows them: "Setting up AI · [position] of [count] · [percent]%".
 *
 * @param position the 1-based download in progress.
 * @param percent overall 0..100, null while no size is known.
 * @param failed a download failed and nothing is running any more: the banner offers a way back to the models.
 */
data class DownloadSummary(
    val position: Int,
    val count: Int,
    val percent: Int?,
    val failed: Boolean = false,
)

/** What Home's model banner says. */
sealed interface ModelBannerState {
    /** No banner. */
    data object Hidden : ModelBannerState

    /** The user postponed the setup and no model is installed: "AI model not installed · Install". */
    data object Install : ModelBannerState

    /** Models are downloading in the background: the progress banner. */
    data class Downloading(val summary: DownloadSummary) : ModelBannerState

    /** A download failed: the error banner. */
    data class Failed(val summary: DownloadSummary) : ModelBannerState
}
