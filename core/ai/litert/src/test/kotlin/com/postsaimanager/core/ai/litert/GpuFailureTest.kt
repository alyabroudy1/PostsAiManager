package com.postsaimanager.core.ai.litert

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GpuFailureTest {

    @Test
    fun `GPU and OpenCL errors are recognised`() {
        assertThat(GpuFailure.matches("Error: Can not find OpenCL library on this device")).isTrue()
        assertThat(GpuFailure.matches("Error: GPU delegate failed to prepare")).isTrue()
    }

    @Test
    fun `other errors and no message are not`() {
        assertThat(GpuFailure.matches("Error: input too long")).isFalse()
        assertThat(GpuFailure.matches(null)).isFalse()
    }
}
