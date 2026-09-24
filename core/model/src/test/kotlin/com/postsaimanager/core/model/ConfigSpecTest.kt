package com.postsaimanager.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [inferenceConfigSchema] and the `effectiveValue` extensions — in particular the
 * "thinking" switch's default, which changed from on to off (see its KDoc in ConfigSpec.kt):
 * on-device CPU decode is slow enough that a Qwen3/3.5 reasoning trace can add tens of
 * seconds before the first visible answer token, so a user who has never touched the switch
 * should not pay that cost by default.
 */
class ConfigSpecTest {

    private val gb = 1024L * 1024L * 1024L

    private val device = DeviceCapability(
        totalRamBytes = 8 * gb,
        availableRamBytes = 8 * gb,
        freeStorageBytes = 50 * gb,
        supportedAbis = listOf("arm64-v8a"),
    )

    private fun schema(defaults: InferenceConfig = InferenceConfig.defaults(device, 4096)) =
        inferenceConfigSchema(device, model = null, defaults = defaults)

    @Nested
    @DisplayName("thinking switch")
    inner class ThinkingSwitch {

        @Test
        fun `defaults to off`() {
            val thinking = schema().filterIsInstance<ConfigSpec.Switch>().first { it.key == "thinking" }
            assertThat(thinking.default).isFalse()
        }

        @Test
        fun `a user who never touched the setting gets it off`() {
            val thinking = schema().filterIsInstance<ConfigSpec.Switch>().first { it.key == "thinking" }
            assertThat(thinking.effectiveValue(InferenceOverrides.NONE)).isFalse()
        }

        @Test
        fun `a user who explicitly turned it on keeps it on`() {
            val thinking = schema().filterIsInstance<ConfigSpec.Switch>().first { it.key == "thinking" }
            val overrides = InferenceOverrides(thinkingEnabled = true)
            assertThat(thinking.effectiveValue(overrides)).isTrue()
        }

        @Test
        fun `a user who explicitly turned it off stays off`() {
            val thinking = schema().filterIsInstance<ConfigSpec.Switch>().first { it.key == "thinking" }
            val overrides = InferenceOverrides(thinkingEnabled = false)
            assertThat(thinking.effectiveValue(overrides)).isFalse()
        }
    }
}
