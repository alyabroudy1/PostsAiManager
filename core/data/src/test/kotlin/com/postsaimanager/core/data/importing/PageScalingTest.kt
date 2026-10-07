package com.postsaimanager.core.data.importing

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.importing.ImportLimits
import org.junit.jupiter.api.Test

/** Page sizes: the longest side becomes about 2480 px (A4 at 300 dpi), the shape is kept. */
class PageScalingTest {

    private val cap = ImportLimits.PAGE_LONGEST_SIDE_PX

    @Test
    fun `an A4 PDF page in points renders to about 1752 by 2480`() {
        assertThat(PageScaling.fit(595, 842, cap)).isEqualTo(1752 to 2480)
    }

    @Test
    fun `a landscape page keeps its shape`() {
        assertThat(PageScaling.fit(842, 595, cap)).isEqualTo(2480 to 1752)
    }

    @Test
    fun `a tiny page is scaled up to scan size, a huge one down to it`() {
        assertThat(PageScaling.fit(100, 140, cap).second).isEqualTo(cap)
        assertThat(PageScaling.fit(5000, 7000, cap).second).isEqualTo(cap)
    }

    @Test
    fun `a photo is shrunk to the cap but never enlarged`() {
        assertThat(PageScaling.fitDown(4000, 3000, cap)).isEqualTo(2480 to 1860)
        assertThat(PageScaling.fitDown(1200, 900, cap)).isEqualTo(1200 to 900)
    }

    @Test
    fun `nonsense sizes never give a zero side`() {
        assertThat(PageScaling.fit(0, 0, cap)).isEqualTo(1 to 1)
        assertThat(PageScaling.fit(1, 100_000, cap).first).isAtLeast(1)
    }
}
