package com.postsaimanager.core.ai.embed.install

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Guards the shape of the pin.
 *
 * These cannot tell whether the hash is *right* — only a download can, which
 * `EmbeddingModelInstallTest` does on device. What they catch is the pin being quietly
 * weakened: a URL edited to a branch, a hash truncated in a merge, a size left at zero.
 * Each of those turns a fail-closed integrity check into a check that passes on the wrong
 * bytes, and none of them is visible in review.
 */
class EmbeddingModelReleaseTest {

    @Test
    @DisplayName("every asset URL names the pinned revision, never a branch")
    fun `urls are pinned to an immutable revision`() {
        EmbeddingModelRelease.all.forEach { asset ->
            assertThat(asset.url).contains(EmbeddingModelRelease.REVISION)
            // A branch would let the bytes change under a fixed hash, at which point the
            // integrity check starts rejecting downloads that are doing nothing wrong —
            // and the failure looks like a network problem, not a pinning mistake.
            assertThat(asset.url).doesNotContain("/resolve/main/")
        }
    }

    @Test
    fun `the revision is a full commit sha`() {
        // An abbreviated sha is ambiguous and can become ambiguous later as a repository
        // grows.
        assertThat(EmbeddingModelRelease.REVISION).hasLength(40)
        assertThat(EmbeddingModelRelease.REVISION).matches("[0-9a-f]{40}")
    }

    @Test
    fun `every asset carries a full sha256 and a real size`() {
        EmbeddingModelRelease.all.forEach { asset ->
            assertThat(asset.sha256).matches("[0-9a-f]{64}")
            assertThat(asset.sizeBytes).isGreaterThan(0L)
        }
    }

    @Test
    @DisplayName("the weights and the vocabulary are distinct assets")
    fun `assets do not collide`() {
        // A copy-paste that pointed both at the same URL would install the vocabulary twice
        // and leave the model file holding text.
        assertThat(EmbeddingModelRelease.model.url)
            .isNotEqualTo(EmbeddingModelRelease.vocabulary.url)
        assertThat(EmbeddingModelRelease.model.sha256)
            .isNotEqualTo(EmbeddingModelRelease.vocabulary.sha256)
    }

    @Test
    fun `total bytes covers every asset`() {
        assertThat(EmbeddingModelRelease.totalBytes)
            .isEqualTo(EmbeddingModelRelease.all.sumOf { it.sizeBytes })
        assertThat(EmbeddingModelRelease.all).hasSize(2)
    }
}
