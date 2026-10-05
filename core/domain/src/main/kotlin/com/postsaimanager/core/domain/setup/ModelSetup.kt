package com.postsaimanager.core.domain.setup

import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.model.SetupNeed
import com.postsaimanager.core.model.SetupOffer
import com.postsaimanager.core.model.SetupProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/**
 * The downloads behind first-run setup: the reader model, the chosen chat model and the search model, as one action.
 *
 * Implemented over the existing download machinery (`ModelDownloadWorker`, `EmbeddingModelManager`, the catalog), so the
 * features never see it.
 */
interface ModelSetupGateway {
    /** The chat models on offer with how each suits this phone, and whether anything can be installed at all. */
    suspend fun offer(): SetupOffer

    /**
     * The downloads' state, live, when [chatModelId] is the chosen chat model. A model that finished downloading is registered as
     * installed on the way; the chosen chat model also becomes the active chat model.
     */
    fun progress(chatModelId: String): Flow<SetupProgress>

    /**
     * Enqueues what is not installed yet: the reader (once, also when it is the chosen model), the chosen chat model, the search model.
     * [allowMetered] lets the download use mobile data; otherwise it waits for Wi-Fi.
     *
     * @return false when a model has no verified download source.
     */
    suspend fun start(chatModelId: String, allowMetered: Boolean): Boolean

    /** Stops all the downloads. The partial files stay, so a retry resumes. */
    fun cancel()
}

/** Whether the current connection is metered (mobile data), so the user is asked before a large download. */
fun interface ConnectionMeter {
    fun isMetered(): Boolean
}

/**
 * Whether the app has to offer the AI model setup now: no chat model installed, and the user has not postponed it.
 * A model that appears (installed here or on the Models screen) ends the need.
 */
class ObserveSetupNeedUseCase @Inject constructor(
    private val installedModels: InstalledModelsRepository,
    private val preferences: UserPreferencesRepository,
) {
    operator fun invoke(): Flow<SetupNeed> =
        combine(installedModels.installed, preferences.getUserPreferences()) { installed, prefs ->
            when {
                installed.isNotEmpty() -> SetupNeed.NOT_NEEDED
                prefs.modelSetupSkipped -> SetupNeed.SKIPPED
                else -> SetupNeed.REQUIRED
            }
        }.distinctUntilChanged()
}

/** Records "Skip for now" (true), and clears it again once a model is installed (false). */
class SetModelSetupSkippedUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
) {
    suspend operator fun invoke(skipped: Boolean) {
        preferences.setModelSetupSkipped(skipped)
    }
}
