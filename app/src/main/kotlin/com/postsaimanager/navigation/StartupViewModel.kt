package com.postsaimanager.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.setup.ObserveSetupNeedUseCase
import com.postsaimanager.core.model.SetupNeed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The routes the app opens on. */
internal object StartRoutes {
    val HOME = TopLevelDestination.HOME.route
    const val SETUP = "setup"

    /** First run, or a launch with no chat model that was not postponed, opens the setup; everything else opens Home. */
    fun forNeed(need: SetupNeed): String = if (need == SetupNeed.REQUIRED) SETUP else HOME
}

/**
 * Decides, once per launch, which screen the app opens on. Null until known, so the app shows nothing rather than a flash of Home
 * before the setup. Later changes (a model installed, a skip) do not move the user: they navigate themselves.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    observeSetupNeed: ObserveSetupNeedUseCase,
) : ViewModel() {

    val startRoute: StateFlow<String?> = flow { emit(StartRoutes.forNeed(observeSetupNeed().first())) }
        .catch { emit(StartRoutes.HOME) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
