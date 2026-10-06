package com.postsaimanager.core.domain.extraction.actions

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ActionKindSelectorTest {

    private fun scores(vararg pairs: Pair<ActionKind, Double>) = pairs.toList()

    private val profile = ActionKindProfile(anyThreshold = 0.0, minScore = 0.0, margin = 1.0)

    @Test
    fun `a letter that asks nothing at all chooses nothing whatever the kinds scored`() {
        val chosen = ActionKindSelector.choose(-0.5, scores(ActionKinds.PAY to 5.0, ActionKinds.REPLY to 4.0), profile)
        assertThat(chosen).isEmpty()
    }

    @Test
    fun `the best kind is chosen with any kind within the margin, best first, at most two`() {
        val chosen = ActionKindSelector.choose(
            2.0, scores(ActionKinds.PAY to 3.0, ActionKinds.REPLY to 2.5, ActionKinds.CONTACT to 2.2, ActionKinds.ATTEND to 0.5), profile,
        )
        assertThat(chosen.map { it.kind.id }).containsExactly("pay", "reply").inOrder()
    }

    @Test
    fun `a kind outside the margin is not chosen`() {
        val chosen = ActionKindSelector.choose(2.0, scores(ActionKinds.PAY to 3.0, ActionKinds.REPLY to 1.9), profile)
        assertThat(chosen.map { it.kind.id }).containsExactly("pay")
    }

    @Test
    fun `the habitual lean of a kind is taken off before the kinds are compared`() {
        val biased = profile.copy(kindBias = mapOf("pay" to 0.8))
        val chosen = ActionKindSelector.choose(2.0, scores(ActionKinds.PAY to 3.0, ActionKinds.REPLY to 2.5), biased)
        assertThat(chosen.map { it.kind.id }).containsExactly("reply", "pay").inOrder()
        assertThat(chosen.first { it.kind.id == "pay" }.adjusted).isWithin(1e-9).of(2.2)
        assertThat(chosen.first { it.kind.id == "pay" }.raw).isEqualTo(3.0)
        // Without the bias the order is the other way round.
        assertThat(ActionKindSelector.choose(2.0, scores(ActionKinds.PAY to 3.0, ActionKinds.REPLY to 2.5), profile).map { it.kind.id })
            .containsExactly("pay", "reply").inOrder()
    }

    @Test
    fun `even the best kind must reach the minimum score`() {
        val strict = profile.copy(minScore = 1.0)
        assertThat(ActionKindSelector.choose(2.0, scores(ActionKinds.PAY to 0.9, ActionKinds.REPLY to 0.1), strict)).isEmpty()
    }

    @Test
    fun `no kinds scored chooses nothing`() {
        assertThat(ActionKindSelector.choose(2.0, emptyList(), profile)).isEmpty()
    }
}
