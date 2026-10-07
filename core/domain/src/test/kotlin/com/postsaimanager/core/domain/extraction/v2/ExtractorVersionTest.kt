package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ExtractorVersionTest {

    @Test
    @DisplayName("no stamp at all is outdated")
    fun nullIsOutdated() {
        assertThat(ExtractorVersion.isOutdated(null)).isTrue()
    }

    @Test
    @DisplayName("the current version is up to date")
    fun currentIsUpToDate() {
        assertThat(ExtractorVersion.isOutdated(ExtractorVersion.CURRENT)).isFalse()
    }

    @Test
    @DisplayName("the type-label extractor is extraction-v2-16, and a document the previous one read is outdated")
    fun typeLabelVersion() {
        assertThat(ExtractorVersion.CURRENT).isEqualTo("extraction-v2-16")
        assertThat(ExtractorVersion.isOutdated("extraction-v2-15")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-14")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-13")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-12")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-11")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-10")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-9")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-8")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-7")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-6")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-5")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-4")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-3")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-2")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-1")).isTrue()
    }

    @Test
    @DisplayName("only a document read by the key-information version or later has hint-guided extras")
    fun readsKeyInfo() {
        assertThat(ExtractorVersion.readsKeyInfo("extraction-v2-3")).isTrue()
        assertThat(ExtractorVersion.readsKeyInfo("extraction-v2-4")).isTrue()
        assertThat(ExtractorVersion.readsKeyInfo("extraction-v2-2")).isFalse()
        assertThat(ExtractorVersion.readsKeyInfo(ExtractorVersion.FOUND_VALUES)).isFalse()
        assertThat(ExtractorVersion.readsKeyInfo("entity-extractor-1")).isFalse()
        assertThat(ExtractorVersion.readsKeyInfo(null)).isFalse()
    }

    @Test
    @DisplayName("a lower model version is outdated, an equal or higher one is not")
    fun modelVersionsAreOrdered() {
        assertThat(ExtractorVersion.isOutdated("extraction-v2-1", current = "extraction-v2-2")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-2", current = "extraction-v2-2")).isFalse()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-3", current = "extraction-v2-2")).isFalse()
        // Numeric, not textual: 10 is newer than 9.
        assertThat(ExtractorVersion.isOutdated("extraction-v2-9", current = "extraction-v2-10")).isTrue()
    }

    @Test
    @DisplayName("the retired pattern extractor's stored stamp and unknown stamps are outdated")
    fun patternsAndUnknownAreOutdated() {
        // A stored value: documents written before the pattern extractor was removed still carry it.
        assertThat(ExtractorVersion.isOutdated("entity-extractor-1")).isTrue()
        assertThat(ExtractorVersion.isOutdated("something-else")).isTrue()
    }

    @Test
    @DisplayName("a no-model run is not outdated: re-reading without a model would only reproduce it")
    fun foundValuesIsLeftAlone() {
        assertThat(ExtractorVersion.isOutdated(ExtractorVersion.FOUND_VALUES)).isFalse()
    }
}
