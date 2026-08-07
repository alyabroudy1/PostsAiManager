package com.postsaimanager.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [ModelFit] and [DeviceTier] — the logic that decides whether a user is
 * offered a model, blocked from it, or warned.
 *
 * Kept as pure functions in `:core:model` precisely so they are testable without an
 * Android runtime; the Android-specific measurement lives in `:core:ai:catalog`.
 */
class ModelFitTest {

    private val gb = 1024L * 1024L * 1024L

    private fun descriptor(
        sizeBytes: Long = 2 * gb,
        minRam: Long = 3 * gb,
        url: String? = "https://example.invalid/model.gguf",
        sha: String? = "a".repeat(64),
    ) = AiModelDescriptor(
        id = "qwen-1_5b-q4",
        name = "Qwen 1.5B",
        family = "Qwen",
        parameterCount = "1.5B",
        quantization = "Q4_K_M",
        sizeBytes = sizeBytes,
        minAvailableRamBytes = minRam,
        contextTokens = 4096,
        license = "Apache-2.0",
        downloadUrl = url,
        sha256 = sha,
    )

    private fun device(
        total: Long = 8 * gb,
        available: Long = 4 * gb,
        storage: Long = 50 * gb,
        abis: List<String> = listOf("arm64-v8a"),
    ) = DeviceCapability(total, available, storage, abis)

    @Nested
    @DisplayName("Device tiering")
    inner class Tiering {

        @Test
        fun `tiers by total RAM`() {
            assertThat(DeviceTier.ofTotalRam(3 * gb)).isEqualTo(DeviceTier.TIER_0)
            assertThat(DeviceTier.ofTotalRam(4 * gb)).isEqualTo(DeviceTier.TIER_1)
            assertThat(DeviceTier.ofTotalRam(6 * gb)).isEqualTo(DeviceTier.TIER_2)
            assertThat(DeviceTier.ofTotalRam(12 * gb)).isEqualTo(DeviceTier.TIER_3)
        }

        @Test
        fun `arm64 and x86_64 are supported, 32-bit is not`() {
            assertThat(device(abis = listOf("arm64-v8a")).hasSupportedAbi).isTrue()
            assertThat(device(abis = listOf("x86_64")).hasSupportedAbi).isTrue()
            assertThat(device(abis = listOf("armeabi-v7a")).hasSupportedAbi).isFalse()
        }
    }

    @Nested
    @DisplayName("Fit evaluation")
    inner class Fit {

        @Test
        fun `fits when memory and storage are ample`() {
            assertThat(ModelFit.evaluate(descriptor(), device())).isEqualTo(ModelFit.Fits)
        }

        @Test
        @DisplayName("available memory, not total, decides loadability")
        fun `flagship with low free memory is blocked from loading but may still download`() {
            // The real measurement from the test device: 11.3 GB total, 2.5 GB available.
            // Tiering on total alone would call this Tier 3 and then hit an OOM kill.
            val s23 = device(total = 11 * gb, available = 2_500_000_000L)

            val fit = ModelFit.evaluate(descriptor(minRam = 3 * gb), s23)

            assertThat(fit).isInstanceOf(ModelFit.InsufficientAvailableMemory::class.java)
            assertThat(fit.canDownload).isTrue() // recoverable — free memory and retry
        }

        @Test
        fun `model larger than total RAM is permanently out of reach`() {
            val fit = ModelFit.evaluate(descriptor(minRam = 12 * gb), device(total = 4 * gb))

            assertThat(fit).isInstanceOf(ModelFit.TooLargeForDevice::class.java)
            assertThat(fit.canDownload).isFalse() // no point downloading it
        }

        @Test
        fun `insufficient storage blocks download`() {
            val fit = ModelFit.evaluate(descriptor(sizeBytes = 4 * gb), device(storage = 1 * gb))
            assertThat(fit).isInstanceOf(ModelFit.InsufficientStorage::class.java)
            assertThat(fit.canDownload).isFalse()
        }

        @Test
        fun `storage headroom is reserved above the model size`() {
            // Exactly the model size free is NOT enough — 512 MB headroom is kept for the OS.
            val fit = ModelFit.evaluate(descriptor(sizeBytes = 2 * gb), device(storage = 2 * gb))
            assertThat(fit).isInstanceOf(ModelFit.InsufficientStorage::class.java)
        }

        @Test
        fun `32-bit device is rejected`() {
            val fit = ModelFit.evaluate(descriptor(), device(abis = listOf("armeabi-v7a")))
            assertThat(fit).isEqualTo(ModelFit.UnsupportedAbi)
        }

        @Test
        @DisplayName("a descriptor without an integrity hash can never be installed")
        fun `missing sha256 blocks installation`() {
            val fit = ModelFit.evaluate(descriptor(sha = null), device())
            assertThat(fit).isEqualTo(ModelFit.NotInstallable)
            assertThat(fit.canDownload).isFalse()
        }

        @Test
        fun `missing download url blocks installation`() {
            assertThat(ModelFit.evaluate(descriptor(url = null), device()))
                .isEqualTo(ModelFit.NotInstallable)
        }

        @Test
        @DisplayName("integrity is checked before capability — an unverifiable model is never offered")
        fun `unverifiable model reports NotInstallable even when the device is capable`() {
            val fit = ModelFit.evaluate(descriptor(sha = null), device(total = 16 * gb, available = 12 * gb))
            assertThat(fit).isEqualTo(ModelFit.NotInstallable)
        }
    }
}
