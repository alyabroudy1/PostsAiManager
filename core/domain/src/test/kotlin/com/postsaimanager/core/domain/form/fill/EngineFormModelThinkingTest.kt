package com.postsaimanager.core.domain.form.fill

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EngineFormModelThinkingTest {

    private val closed = "<think>\n\n</think>\n\n"

    @Test
    fun `a generation prompt that ends at the bare assistant tag gets an empty closed reasoning block`() {
        assertThat(EngineFormModel.closeThinking("<|im_start|>assistant\n")).isEqualTo("<|im_start|>assistant\n$closed")
    }

    @Test
    fun `an open reasoning block is closed and a closed one is left alone`() {
        assertThat(EngineFormModel.closeThinking("<|im_start|>assistant\n<think>\n")).isEqualTo("<|im_start|>assistant\n<think>\n\n</think>\n\n")
        assertThat(EngineFormModel.closeThinking("a\n$closed")).isEqualTo("a\n$closed")
    }
}
