package com.postsaimanager.navigation

import androidx.lifecycle.ViewModel
import com.postsaimanager.core.common.notification.NotificationRoute
import com.postsaimanager.core.domain.setup.ObserveSetupNeedUseCase
import com.postsaimanager.core.model.SetupNeed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Gives the navigation the pending notification route, already resolved to a screen, and lets it mark the route as applied. */
@HiltViewModel
class NotificationRouteViewModel @Inject constructor(
    private val inbox: NotificationRouteInbox,
    private val observeSetupNeed: ObserveSetupNeedUseCase,
) : ViewModel() {

    val pending: StateFlow<NotificationRoute?> = inbox.pending

    /** The navigation route for [route]; the setup state is read now, so a tap on a download notification lands where it is relevant. */
    suspend fun resolve(route: NotificationRoute): String {
        val need = runCatching { observeSetupNeed().first() }.getOrDefault(SetupNeed.NOT_NEEDED)
        return NotificationTargets.resolve(route, need)
    }

    fun consume(route: NotificationRoute) = inbox.consume(route)
}
