package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.Validation
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner.Caps
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner.Check
import org.junit.jupiter.api.Test

class ConfidenceCombinerTest {

    private fun combine(word: String, vararg checks: Check) = ConfidenceCombiner.combine(ConfidenceCombiner.aiScore(word), checks.toList())

    @Test
    fun `the model's words map to 0_4, 0_7 and 0_9, anything else to 0_5`() {
        assertThat(ConfidenceCombiner.aiScore("LOW")).isEqualTo(0.4f)
        assertThat(ConfidenceCombiner.aiScore("medium")).isEqualTo(0.7f)
        assertThat(ConfidenceCombiner.aiScore(" HIGH ")).isEqualTo(0.9f)
        assertThat(ConfidenceCombiner.aiScore(null)).isEqualTo(0.5f)
        assertThat(ConfidenceCombiner.aiScore("certain")).isEqualTo(0.5f)
    }

    @Test
    fun `with no failed check the final confidence is the model's own`() {
        val c = combine("HIGH", Check.Pass, Check.Pass)
        assertThat(c.final).isEqualTo(0.9f)
        assertThat(c.blocked).isFalse()
        assertThat(c.notes).isEmpty()
    }

    @Test
    fun `a pass never raises the confidence above the model's`() {
        assertThat(combine("LOW", Check.Pass).final).isEqualTo(0.4f)
        assertThat(combine("MEDIUM", Check.Pass, Check.Pass, Check.Pass).final).isEqualTo(0.7f)
    }

    @Test
    fun `a failed check caps the confidence, and the lowest cap wins`() {
        val c = combine("HIGH", Check.Cap(Caps.QUOTE_EXACT, "quoted"), Check.Cap(Caps.INVALID, "bad checksum"))
        assertThat(c.final).isEqualTo(Caps.INVALID)
        assertThat(c.notes).containsExactly("quoted", "bad checksum").inOrder()
    }

    @Test
    fun `a cap above the model's own confidence leaves it alone`() {
        assertThat(combine("LOW", Check.Cap(Caps.QUOTE_EXACT, "quoted")).final).isEqualTo(0.4f)
    }

    @Test
    fun `only a blocking cap flags the value`() {
        assertThat(combine("HIGH", Check.Cap(Caps.QUOTE_EXACT, "quoted", blocking = false)).blocked).isFalse()
        assertThat(combine("HIGH", Check.Cap(Caps.ROLE_MISMATCH, "wrong role")).blocked).isTrue()
    }

    @Test
    fun `the caps are ordered as agreed`() {
        assertThat(Caps.INVALID).isEqualTo(0.3f)
        assertThat(Caps.CONFLICT).isEqualTo(0.3f)
        assertThat(Caps.ROLE_MISMATCH).isEqualTo(0.4f)
        assertThat(Caps.QUOTE_FUZZY).isEqualTo(0.45f)
        assertThat(Caps.QUOTE_EXACT).isEqualTo(0.6f)
        assertThat(Caps.UNCHECKED).isEqualTo(0.6f)
    }

    private fun value(confidence: Float, blocked: Boolean = false) = SlotValue(
        slot = null, candidateId = null, value = "x", normalized = "x", page = null, bbox = null, evidence = "",
        origin = SlotOrigin.MODEL_CHOICE, aiConfidence = 0.9f, confidence = confidence, validation = Validation.Valid, blocked = blocked,
    )

    @Test
    fun `a value needs review when it is blocked or below 0_75`() {
        assertThat(value(0.9f).needsReview).isFalse()
        assertThat(value(0.7f).needsReview).isTrue()
        assertThat(value(0.9f, blocked = true).needsReview).isTrue()
    }
}
