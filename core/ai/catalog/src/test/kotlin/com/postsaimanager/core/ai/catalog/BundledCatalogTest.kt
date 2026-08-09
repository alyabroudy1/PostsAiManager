package com.postsaimanager.core.ai.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Guards the shape of the pinned catalog.
 *
 * These cannot say whether a hash is *correct* — only a download can, and
 * `ModelCatalogDownloadTest` does that on device. What they catch is the pin being quietly
 * weakened: a URL edited to a branch, a hash truncated in a merge, an entry added without
 * one. Each of those turns a fail-closed integrity check into a check that passes on the
 * wrong bytes, and none is visible in review.
 */
class BundledCatalogTest {

    @Test
    @DisplayName("every model can actually be installed")
    fun `all entries carry a url and a hash`() {
        // The state this replaced: every entry was NotInstallable, so the model manager
        // listed models that could never be downloaded.
        BundledCatalog.models.forEach { model ->
            assertThat(model.isInstallable).isTrue()
        }
        assertThat(BundledCatalog.models).isNotEmpty()
    }

    @Test
    @DisplayName("URLs name an immutable revision, never a branch")
    fun `urls are pinned`() {
        BundledCatalog.models.forEach { model ->
            val url = model.downloadUrl!!
            // A branch would let the bytes change under a fixed hash, and the resulting
            // failure looks like a network problem rather than a pinning mistake.
            assertThat(url).doesNotContain("/resolve/main/")
            assertThat(url).startsWith("https://")
            // 40-hex commit between /resolve/ and the filename.
            assertThat(url).matches(".*/resolve/[0-9a-f]{40}/.*")
        }
    }

    @Test
    fun `hashes are full sha256 and distinct`() {
        val hashes = BundledCatalog.models.map { it.sha256!! }
        hashes.forEach { assertThat(it).matches("[0-9a-f]{64}") }
        // A copy-paste that reused a hash would make one model permanently unverifiable.
        assertThat(hashes).containsNoDuplicates()
    }

    @Test
    fun `ids and download urls are unique`() {
        assertThat(BundledCatalog.models.map { it.id }).containsNoDuplicates()
        assertThat(BundledCatalog.models.map { it.downloadUrl }).containsNoDuplicates()
    }

    @Test
    @DisplayName("sizes are real byte counts, not rounded guesses")
    fun `sizes are exact`() {
        BundledCatalog.models.forEach { model ->
            assertThat(model.sizeBytes).isGreaterThan(100L * 1024 * 1024)
            // Rounded values (400 MB exactly) mean someone estimated rather than reading
            // the file listing, and the downloader uses this to report progress.
            assertThat(model.sizeBytes % (1024L * 1024L)).isNotEqualTo(0L)
        }
    }

    @Test
    @DisplayName("memory needed always exceeds the file size")
    fun `ram estimates leave room for the runtime`() {
        BundledCatalog.models.forEach { model ->
            // Weights are not the whole cost — the KV cache and the runtime sit on top. An
            // estimate below the file size would let a device accept a model it cannot load.
            assertThat(model.minAvailableRamBytes).isGreaterThan(model.sizeBytes)
        }
    }

    @Test
    fun `the range spans small and capable devices`() {
        val sizes = BundledCatalog.models.map { it.sizeBytes }
        // Only offering large models makes the app useless on a cheap phone; only small
        // ones caps how well it can ever read a letter.
        assertThat(sizes.min()).isLessThan(600L * 1024 * 1024)
        assertThat(sizes.max()).isGreaterThan(2L * 1024 * 1024 * 1024)
    }
}
