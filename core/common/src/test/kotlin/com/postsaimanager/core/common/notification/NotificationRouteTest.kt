package com.postsaimanager.core.common.notification

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NotificationRouteTest {

    @Test
    fun `every route survives a round trip through its URI`() {
        listOf(
            NotificationRoute.Document("1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"),
            NotificationRoute.Document("odd id/with?chars&ünï"),
            NotificationRoute.Documents,
            NotificationRoute.Downloads,
            NotificationRoute.Models,
            NotificationRoute.Setup,
        ).forEach { assertThat(NotificationRoute.parse(it.toUri())).isEqualTo(it) }
    }

    @Test
    fun `document uri has the documented shape`() {
        assertThat(NotificationRoute.Document("abc").toUri()).isEqualTo("postsaimanager://document/abc")
        assertThat(NotificationRoute.Models.toUri()).isEqualTo("postsaimanager://models")
    }

    @Test
    fun `an id cannot add a path segment`() {
        assertThat(NotificationRoute.Document("a/b").toUri()).doesNotContain("a/b")
    }

    @Test
    fun `foreign or malformed uris are not routes`() {
        assertThat(NotificationRoute.parse(null)).isNull()
        assertThat(NotificationRoute.parse("https://document/abc")).isNull()
        assertThat(NotificationRoute.parse("postsaimanager://document")).isNull()
        assertThat(NotificationRoute.parse("postsaimanager://document/a/b")).isNull()
        assertThat(NotificationRoute.parse("postsaimanager://elsewhere")).isNull()
        assertThat(NotificationRoute.parse("postsaimanager://models/extra")).isNull()
    }

    @Test
    fun `request codes differ per document`() {
        val a = NotificationIntents.requestCode(NotificationRoute.Document("a"))
        val b = NotificationIntents.requestCode(NotificationRoute.Document("b"))
        assertThat(a).isNotEqualTo(b)
    }
}
