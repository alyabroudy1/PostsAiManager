package com.postsaimanager.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [inferenceConfigSchema] and the `effectiveValue` extensions — in particular the
 * "thinking" Off/Low/High effort choice's default, which is Off (see its KDoc in
 * ConfigSpec.kt): on-device CPU decode is slow enough that an unbounded Qwen3/3.5 reasoning
 * trace can add tens of seconds before the first visible answer token, so a user who has
 * never touched the setting should not pay that cost by default.
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

    private fun thinkingChoice() =
        schema().filterIsInstance<ConfigSpec.Choice>().first { it.key == "thinkingEffort" }

    @Nested
    @DisplayName("thinking effort")
    inner class ThinkingEffortChoice {

        @Test
        fun `offers Off, Low and High`() {
            assertThat(thinkingChoice().options).containsExactly("OFF", "LOW", "HIGH")
        }

        @Test
        fun `defaults to off`() {
            assertThat(thinkingChoice().default).isEqualTo("OFF")
        }

        @Test
        fun `a user who never touched the setting gets it off`() {
            assertThat(thinkingChoice().effectiveValue(InferenceOverrides.NONE)).isEqualTo("OFF")
        }

        @Test
        fun `a user who explicitly picked High keeps High`() {
            val overrides = InferenceOverrides(thinkingEffort = ThinkingEffort.HIGH)
            assertThat(thinkingChoice().effectiveValue(overrides)).isEqualTo("HIGH")
        }

        @Test
        fun `a user who explicitly picked Low keeps Low`() {
            val overrides = InferenceOverrides(thinkingEffort = ThinkingEffort.LOW)
            assertThat(thinkingChoice().effectiveValue(overrides)).isEqualTo("LOW")
        }

        @Test
        fun `a user who explicitly picked Off stays off`() {
            val overrides = InferenceOverrides(thinkingEffort = ThinkingEffort.OFF)
            assertThat(thinkingChoice().effectiveValue(overrides)).isEqualTo("OFF")
        }
    }

    @Nested
    @DisplayName("ThinkingEffort.fromLabel")
    inner class ThinkingEffortFromLabel {

        @Test
        fun `parses known labels case-insensitively`() {
            assertThat(ThinkingEffort.fromLabel("low")).isEqualTo(ThinkingEffort.LOW)
            assertThat(ThinkingEffort.fromLabel("HIGH")).isEqualTo(ThinkingEffort.HIGH)
            assertThat(ThinkingEffort.fromLabel("Off")).isEqualTo(ThinkingEffort.OFF)
        }

        @Test
        fun `unknown labels fall back to Off`() {
            assertThat(ThinkingEffort.fromLabel("maximum overdrive")).isEqualTo(ThinkingEffort.OFF)
        }
    }
}
