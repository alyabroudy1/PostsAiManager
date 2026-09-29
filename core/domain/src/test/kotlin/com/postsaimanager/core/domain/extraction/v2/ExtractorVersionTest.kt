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
    @DisplayName("a lower model version is outdated, an equal or higher one is not")
    fun modelVersionsAreOrdered() {
        assertThat(ExtractorVersion.isOutdated("extraction-v2-1", current = "extraction-v2-2")).isTrue()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-2", current = "extraction-v2-2")).isFalse()
        assertThat(ExtractorVersion.isOutdated("extraction-v2-3", current = "extraction-v2-2")).isFalse()
        // Numeric, not textual: 10 is newer than 9.
        assertThat(ExtractorVersion.isOutdated("extraction-v2-9", current = "extraction-v2-10")).isTrue()
    }

    @Test
    @DisplayName("the pattern extractor's stamp and unknown stamps are outdated")
    fun patternsAndUnknownAreOutdated() {
        assertThat(ExtractorVersion.isOutdated(ExtractorVersion.PATTERNS)).isTrue()
        assertThat(ExtractorVersion.isOutdated("something-else")).isTrue()
    }

    @Test
    @DisplayName("a no-model run is not outdated: re-reading without a model would only reproduce it")
    fun foundValuesIsLeftAlone() {
        assertThat(ExtractorVersion.isOutdated(ExtractorVersion.FOUND_VALUES)).isFalse()
    }
}
