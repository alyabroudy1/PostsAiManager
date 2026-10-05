package com.postsaimanager.navigation

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.notification.NotificationRoute
import com.postsaimanager.core.model.SetupNeed
import org.junit.jupiter.api.Test

class NotificationRouteInboxTest {

    private val inbox = NotificationRouteInbox()

    @Test
    fun `an intent uri becomes the pending route`() {
        inbox.offer("postsaimanager://document/abc")
        assertThat(inbox.pending.value).isEqualTo(NotificationRoute.Document("abc"))
    }

    @Test
    fun `a foreign or missing uri changes nothing`() {
        inbox.offer(null)
        inbox.offer("https://example.com/x")
        assertThat(inbox.pending.value).isNull()
        inbox.offer("postsaimanager://models")
        inbox.offer("https://example.com/x")
        assertThat(inbox.pending.value).isEqualTo(NotificationRoute.Models)
    }

    @Test
    fun `a route offered while the app is locked waits until the navigation consumes it, once`() {
        // The navigation only exists after unlock, so nobody reads the inbox while locked: the route must still be there.
        inbox.offer("postsaimanager://document/abc")
        assertThat(inbox.pending.value).isEqualTo(NotificationRoute.Document("abc"))

        inbox.consume(NotificationRoute.Document("abc"))
        assertThat(inbox.pending.value).isNull()
    }

    @Test
    fun `consuming an old route keeps a newer one that arrived meanwhile`() {
        inbox.offer("postsaimanager://document/a")
        inbox.offer("postsaimanager://document/b")
        inbox.consume(NotificationRoute.Document("a"))
        assertThat(inbox.pending.value).isEqualTo(NotificationRoute.Document("b"))
    }

    @Test
    fun `routes resolve to screens`() {
        fun r(route: NotificationRoute, need: SetupNeed = SetupNeed.NOT_NEEDED) = NotificationTargets.resolve(route, need)
        assertThat(r(NotificationRoute.Document("x"))).isEqualTo("document/x")
        assertThat(r(NotificationRoute.Documents)).isEqualTo(StartRoutes.HOME)
        assertThat(r(NotificationRoute.Models)).isEqualTo("models")
        assertThat(r(NotificationRoute.Setup)).isEqualTo(StartRoutes.SETUP)
        assertThat(r(NotificationRoute.Downloads, SetupNeed.REQUIRED)).isEqualTo(StartRoutes.SETUP)
        assertThat(r(NotificationRoute.Downloads, SetupNeed.SKIPPED)).isEqualTo("models")
        assertThat(r(NotificationRoute.Downloads, SetupNeed.NOT_NEEDED)).isEqualTo("models")
    }
}
