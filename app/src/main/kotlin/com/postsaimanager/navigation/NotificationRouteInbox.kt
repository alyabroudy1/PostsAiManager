package com.postsaimanager.navigation

import com.postsaimanager.core.common.notification.NotificationRoute
import com.postsaimanager.core.model.SetupNeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the route of a tapped notification until the navigation consumes it, exactly once.
 *
 * The activity [offer]s on `onCreate` and `onNewIntent`; the navigation only exists inside the app lock gate, so while the app
 * is locked the route simply stays here and is applied after unlock. Nothing reads it any other way, so the lock cannot be bypassed.
 */
@Singleton
class NotificationRouteInbox @Inject constructor() {

    private val _pending = MutableStateFlow<NotificationRoute?>(null)
    val pending: StateFlow<NotificationRoute?> = _pending.asStateFlow()

    /** The data URI of an incoming intent; anything that is not one of our routes is ignored (the pending route stays). */
    fun offer(dataString: String?) {
        NotificationRoute.parse(dataString)?.let { _pending.value = it }
    }

    /** Clears [route] once it has been applied; a newer route that arrived meanwhile is kept. */
    fun consume(route: NotificationRoute) {
        _pending.compareAndSet(route, null)
    }
}

/** Maps a [NotificationRoute] to the navigation route it opens. */
internal object NotificationTargets {

    fun resolve(route: NotificationRoute, setupNeed: SetupNeed): String = when (route) {
        is NotificationRoute.Document -> "document/${route.documentId}"
        NotificationRoute.Documents -> StartRoutes.HOME
        NotificationRoute.Models -> MODELS
        NotificationRoute.Setup -> StartRoutes.SETUP
        // Setup still in progress (required and not postponed): go there; otherwise the Models screen.
        NotificationRoute.Downloads -> if (setupNeed == SetupNeed.REQUIRED) StartRoutes.SETUP else MODELS
    }

    const val MODELS = "models"
}
