package com.postsaimanager.setup

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.download.DownloadBoard
import com.postsaimanager.core.model.DownloadSummary
import org.junit.jupiter.api.Test

/** The download board as Home's banner reads it. */
class DownloadSummaryMappingTest {

    private val board = DownloadBoard().queued("a", "A", 100L).queued("b", "B", 300L)

    @Test
    fun `an empty board has no summary`() {
        assertThat(summaryOf(DownloadBoard())).isNull()
    }

    @Test
    fun `running downloads give the position, the count and the overall percent`() {
        val running = board.downloading("a", "A", 100L, 100L).done("a").downloading("b", "B", 150L, 300L)

        assertThat(summaryOf(running)).isEqualTo(DownloadSummary(position = 2, count = 2, percent = 62))
    }

    @Test
    fun `successive progress moves the percent`() {
        val first = summaryOf(board.downloading("b", "B", 30L, 300L))
        val second = summaryOf(board.downloading("b", "B", 240L, 300L))

        assertThat(second!!.percent!!).isGreaterThan(first!!.percent!!)
    }

    @Test
    fun `a failed item with nothing running is the failed summary`() {
        val failed = board.done("a").failed("b")

        assertThat(summaryOf(failed)!!.failed).isTrue()
    }

    @Test
    fun `a board where everything finished has no summary`() {
        assertThat(summaryOf(board.done("a").done("b"))).isNull()
    }
}
