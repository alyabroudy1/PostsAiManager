package com.postsaimanager

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FeatureFlagsModuleTest {

    @Test
    @DisplayName("form filling is on in a debug build")
    fun onInDebug() {
        assertThat(formFillingFlagFor(debugBuild = true).enabled).isTrue()
    }

    @Test
    @DisplayName("form filling is off in a release build")
    fun offInRelease() {
        assertThat(formFillingFlagFor(debugBuild = false).enabled).isFalse()
    }
}
