package com.postsaimanager.core.ai.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BigCoreCountTest {

    @Test
    fun `cores of the fast clusters are counted`() {
        // 4 little + 3 mid + 1 prime (a 1+3+4 phone, kHz).
        val frequencies = listOf(1_800_000L, 1_800_000L, 1_800_000L, 1_800_000L, 2_800_000L, 2_800_000L, 2_800_000L, 3_300_000L)
        assertThat(AndroidDeviceCapabilities.bigCoreCount(frequencies, processorCount = 8)).isEqualTo(4)
    }

    @Test
    fun `equal cores are all big`() {
        assertThat(AndroidDeviceCapabilities.bigCoreCount(List(4) { 2_000_000L }, processorCount = 4)).isEqualTo(4)
    }

    @Test
    fun `unreadable frequencies fall back to the processor count`() {
        assertThat(AndroidDeviceCapabilities.bigCoreCount(emptyList(), processorCount = 6)).isEqualTo(6)
    }
}
