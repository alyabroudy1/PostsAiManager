package com.postsaimanager.core.ai.embed

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * Tests for [Pooling].
 *
 * This is the arithmetic the ONNX graph does **not** do — it stops at one vector per
 * token. Getting pooling wrong yields embeddings that are subtly poor rather than
 * obviously broken, so search degrades with nothing to point at.
 */
class PoolingTest {

    @Nested
    @DisplayName("Mean pooling")
    inner class MeanPool {

        @Test
        fun `averages the token vectors`() {
            val tokens = arrayOf(
                floatArrayOf(1f, 2f),
                floatArrayOf(3f, 4f),
            )
            val pooled = Pooling.meanPool(tokens, longArrayOf(1, 1))

            assertThat(pooled.toList()).isEqualTo(listOf(2f, 3f))
        }

        @Test
        @DisplayName("padding is excluded — this is the whole point of the mask")
        fun `ignores masked positions`() {
            val tokens = arrayOf(
                floatArrayOf(1f, 1f),
                floatArrayOf(3f, 3f),
                floatArrayOf(100f, 100f), // padding: must not contribute
                floatArrayOf(100f, 100f),
            )
            val pooled = Pooling.meanPool(tokens, longArrayOf(1, 1, 0, 0))

            assertThat(pooled.toList()).isEqualTo(listOf(2f, 2f))
        }

        @Test
        @DisplayName("the same text yields the same vector regardless of padding length")
        fun `padding length does not change the result`() {
            val real = arrayOf(floatArrayOf(1f, 5f), floatArrayOf(3f, 1f))
            val pad = floatArrayOf(-50f, 50f)

            val short = Pooling.meanPool(real, longArrayOf(1, 1))
            val long = Pooling.meanPool(
                real + arrayOf(pad, pad, pad, pad),
                longArrayOf(1, 1, 0, 0, 0, 0),
            )

            // Without masking, the same sentence encoded at two batch lengths would land
            // in two different places and never match itself.
            assertThat(long.toList()).isEqualTo(short.toList())
        }

        @Test
        fun `an all-zero mask yields a zero vector rather than NaN`() {
            val tokens = arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))

            val pooled = Pooling.meanPool(tokens, longArrayOf(0, 0))

            // Dividing by zero would produce NaN, which poisons every later comparison —
            // NaN is neither greater nor less than anything, so ranking silently breaks.
            assertThat(pooled.toList()).isEqualTo(listOf(0f, 0f))
            assertThat(pooled.none { it.isNaN() }).isTrue()
        }

        @Test
        fun `empty input yields an empty vector`() {
            assertThat(Pooling.meanPool(emptyArray(), longArrayOf()).size).isEqualTo(0)
        }

        @Test
        fun `a mask shorter than the sequence stops rather than overruns`() {
            val tokens = arrayOf(floatArrayOf(1f), floatArrayOf(3f), floatArrayOf(99f))
            assertThat(Pooling.meanPool(tokens, longArrayOf(1, 1)).toList()).isEqualTo(listOf(2f))
        }
    }

    @Nested
    @DisplayName("L2 normalisation")
    inner class Normalise {

        @Test
        fun `scales to unit length`() {
            val normalised = Pooling.l2Normalize(floatArrayOf(3f, 4f))

            assertThat(normalised.toList()).isEqualTo(listOf(0.6f, 0.8f))
            val length = sqrt(normalised.sumOf { (it * it).toDouble() })
            assertThat(length).isWithin(1e-6).of(1.0)
        }

        @Test
        fun `preserves direction`() {
            val original = floatArrayOf(1f, 2f, 3f)
            val normalised = Pooling.l2Normalize(original)

            // Ratios between components must be unchanged, or the vector means something
            // different after normalising.
            assertThat((normalised[1] / normalised[0]).toDouble()).isWithin(1e-5).of(2.0)
            assertThat((normalised[2] / normalised[0]).toDouble()).isWithin(1e-5).of(3.0)
        }

        @Test
        fun `a zero vector is returned unchanged rather than dividing by zero`() {
            assertThat(Pooling.l2Normalize(floatArrayOf(0f, 0f)).toList()).isEqualTo(listOf(0f, 0f))
        }

        @Test
        fun `normalising twice changes nothing`() {
            val once = Pooling.l2Normalize(floatArrayOf(5f, 12f))
            val twice = Pooling.l2Normalize(once)

            assertThat(twice.toList()).isEqualTo(once.toList())
        }
    }
}
