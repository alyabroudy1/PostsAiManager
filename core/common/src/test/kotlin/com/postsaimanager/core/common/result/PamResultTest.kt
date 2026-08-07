package com.postsaimanager.core.common.result

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for the [PamResult] combinators.
 *
 * These sit on the boundary every layer crosses — architecture rule 12 says repositories
 * return `PamResult` and exceptions never cross a layer — so their behaviour under error
 * needs to be nailed down.
 *
 * This suite doubles as proof that the `pam.test-conventions` plugin activated the JUnit
 * platform here: before it, `:core:common` declared JUnit 5 with no `useJUnitPlatform()`,
 * so tests in this module would have been silently skipped.
 */
class PamResultTest {

    private val boom = IllegalStateException("boom")

    @Test
    fun `map transforms success`() {
        val result: PamResult<Int> = PamResult.Success(21)
        assertThat(result.map { it * 2 }).isEqualTo(PamResult.Success(42))
    }

    @Test
    fun `map leaves errors untouched and does not invoke the transform`() {
        var called = false
        val result: PamResult<Int> = PamResult.Error(PamError.DatabaseError(boom))

        val mapped = result.map { called = true; it * 2 }

        assertThat(called).isFalse()
        assertThat(mapped).isInstanceOf(PamResult.Error::class.java)
    }

    @Test
    fun `onSuccess runs only on success and returns the receiver`() {
        var seen: Int? = null
        val original: PamResult<Int> = PamResult.Success(7)

        val returned = original.onSuccess { seen = it }

        assertThat(seen).isEqualTo(7)
        assertThat(returned).isSameInstanceAs(original)
    }

    @Test
    fun `onError runs only on error and receives the PamError`() {
        var seen: PamError? = null
        val error = PamError.DatabaseError(boom)

        PamResult.Error(error).onError { seen = it }
        PamResult.Success(1).onError { seen = PamError.DatabaseError(null) }

        assertThat(seen).isSameInstanceAs(error)
    }

    @Test
    fun `getOrNull unwraps success and nulls errors`() {
        assertThat(PamResult.Success("x").getOrNull()).isEqualTo("x")

        // The explicit type is required: `PamResult.Error` is `PamResult<Nothing>`, so
        // `getOrNull()` returns `Nothing?` and assertion overloads become ambiguous.
        // Worth knowing — call sites generally need the declared result type in scope.
        val failed: PamResult<String> = PamResult.Error(PamError.DatabaseError(boom))
        assertThat(failed.getOrNull()).isNull()
    }

    @Test
    fun `getOrThrow rethrows the original cause when present`() {
        val thrown = assertThrows<IllegalStateException> {
            PamResult.Error(PamError.DatabaseError(boom)).getOrThrow()
        }
        assertThat(thrown).isSameInstanceAs(boom)
    }

    @Test
    fun `getOrThrow falls back to the user message when there is no cause`() {
        val thrown = assertThrows<IllegalStateException> {
            PamResult.Error(PamError.DatabaseError(null)).getOrThrow()
        }
        assertThat(thrown).isNotSameInstanceAs(boom)
        assertThat(thrown).hasMessageThat().isNotEmpty()
    }

    @Test
    fun `success carrying null is still a success`() {
        // getOrNull() cannot distinguish this from an error — worth knowing before
        // anyone writes `if (getOrNull() == null) handleError()`.
        val result: PamResult<String?> = PamResult.Success(null)
        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        assertThat(result.getOrNull()).isNull()
    }
}
