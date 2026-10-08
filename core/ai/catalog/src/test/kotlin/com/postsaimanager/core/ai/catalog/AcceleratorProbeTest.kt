package com.postsaimanager.core.ai.catalog

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Accelerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AcceleratorProbeTest {

    private val both = setOf(Accelerator.CPU, Accelerator.GPU)

    @Test
    @DisplayName("an answer is returned and remembered")
    fun `answers`() = runTest {
        val probe = AcceleratorProbe(backgroundScope) { both }
        assertThat(probe.current()).isEqualTo(both)
    }

    @Test
    @DisplayName("a busy inference process does not hold the question: CPU alone at first, the earlier answer afterwards")
    fun `busy process`() = runTest {
        var busy = false
        val never = CompletableDeferred<Set<Accelerator>>()
        val probe = AcceleratorProbe(backgroundScope) { if (busy) never.await() else both }

        busy = true
        assertThat(probe.current()).isEqualTo(setOf(Accelerator.CPU))

        busy = false
        never.complete(both)
        assertThat(probe.current()).isEqualTo(both)

        busy = true
        val stuck = CompletableDeferred<Set<Accelerator>>()
        val again = AcceleratorProbe(backgroundScope) { stuck.await() }
        assertThat(again.current()).isEqualTo(setOf(Accelerator.CPU))
    }

    @Test
    @DisplayName("a CPU-only answer (service unreachable) never replaces a richer one")
    fun `cpu only does not downgrade`() = runTest {
        var answer = both
        val probe = AcceleratorProbe(backgroundScope) { answer }
        assertThat(probe.current()).isEqualTo(both)
        answer = setOf(Accelerator.CPU)
        assertThat(probe.current()).isEqualTo(both)
    }
}
