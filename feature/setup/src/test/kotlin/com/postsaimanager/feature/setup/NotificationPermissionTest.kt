package com.postsaimanager.feature.setup

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** When the setup screen asks for the notification permission: Android 13+ only, and only while it is not granted. */
class NotificationPermissionTest {

    @Test
    fun `Android 13 and newer asks while the permission is missing`() {
        assertThat(shouldAskNotificationPermission(sdkInt = 33, granted = false)).isTrue()
        assertThat(shouldAskNotificationPermission(sdkInt = 36, granted = false)).isTrue()
    }

    @Test
    fun `an already granted permission is not asked again`() {
        assertThat(shouldAskNotificationPermission(sdkInt = 34, granted = true)).isFalse()
    }

    @Test
    fun `older Android has no notification permission to ask`() {
        assertThat(shouldAskNotificationPermission(sdkInt = 32, granted = false)).isFalse()
        assertThat(shouldAskNotificationPermission(sdkInt = 26, granted = false)).isFalse()
    }
}
