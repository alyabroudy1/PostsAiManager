package com.postsaimanager.core.download

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The state behind the single download notification: overall progress, the "n of m" position, and when it ends. */
class DownloadBoardTest {

    private val threeQueued = DownloadBoard()
        .queued("reader", "Qwen3.5 0.8B", 600L)
        .queued("chat", "Qwen3.5 2B", 1_300L)
        .queued("search", "Search model", 100L)

    @Test
    fun `queued items wait, and the position starts at 1 of 3`() {
        assertThat(threeQueued.items.map { it.state }).containsExactly(
            DownloadItemState.WAITING, DownloadItemState.WAITING, DownloadItemState.WAITING,
        )
        assertThat(threeQueued.itemCount).isEqualTo(3)
        assertThat(threeQueued.currentPosition).isEqualTo(1)
        assertThat(threeQueued.isActive).isTrue()
        assertThat(threeQueued.overallPercent).isEqualTo(0)
    }

    @Test
    fun `overall progress is the sum of bytes over the sum of totals`() {
        val board = threeQueued
            .downloading("reader", "Qwen3.5 0.8B", 300L, 600L)
            .downloading("chat", "Qwen3.5 2B", 650L, 1_300L)
            .downloading("search", "Search model", 50L, 100L)

        assertThat(board.overallPercent).isEqualTo(50)
        assertThat(board.items.first { it.id == "reader" }.percent).isEqualTo(50)
    }

    @Test
    fun `successive progress reports keep moving the overall figure`() {
        var board = threeQueued
        val seen = (1..4).map { step ->
            board = board.downloading("chat", "Qwen3.5 2B", step * 300L, 1_300L)
            board.overallPercent
        }
        assertThat(seen).isInOrder()
        assertThat(seen.toSet().size).isEqualTo(4)
    }

    @Test
    fun `a finished item counts in full and moves the position on`() {
        val board = threeQueued.downloading("reader", "Qwen3.5 0.8B", 600L, 600L).done("reader")

        assertThat(board.doneCount).isEqualTo(1)
        assertThat(board.currentPosition).isEqualTo(2)
        assertThat(board.overallPercent).isEqualTo((600 * 100) / 2_000)
    }

    @Test
    fun `an item with no bytes yet is preparing, with bytes it is downloading`() {
        val preparing = threeQueued.downloading("search", "Search model", 0L, 100L)
        val downloading = preparing.downloading("search", "Search model", 10L, 100L)

        assertThat(preparing.items.first { it.id == "search" }.state).isEqualTo(DownloadItemState.PREPARING)
        assertThat(downloading.items.first { it.id == "search" }.state).isEqualTo(DownloadItemState.DOWNLOADING)
    }

    @Test
    fun `the enqueue-time total is kept while the worker does not know it`() {
        val board = threeQueued.downloading("chat", "Qwen3.5 2B", 130L, 0L)

        assertThat(board.items.first { it.id == "chat" }.percent).isEqualTo(10)
    }

    @Test
    fun `an unknown total gives no overall figure`() {
        val board = DownloadBoard().queued("a", "A", 0L).downloading("a", "A", 5L, 0L)

        assertThat(board.overallPercent).isNull()
    }

    @Test
    fun `queueing a running item again keeps its progress`() {
        val board = threeQueued.downloading("chat", "Qwen3.5 2B", 650L, 1_300L).queued("chat", "Qwen3.5 2B", 1_300L)

        assertThat(board.items.first { it.id == "chat" }.bytes).isEqualTo(650L)
    }

    @Test
    fun `a retrying worker goes back to waiting and keeps the notification alive`() {
        val board = DownloadBoard().queued("a", "A", 10L).downloading("a", "A", 5L, 10L).waiting("a")

        assertThat(board.items.single().state).isEqualTo(DownloadItemState.WAITING)
        assertThat(board.isActive).isTrue()
    }

    @Test
    fun `the board is inactive once everything is done, failed or cancelled`() {
        val board = threeQueued.done("reader").failed("chat").removed("search")

        assertThat(board.isActive).isFalse()
        assertThat(board.itemCount).isEqualTo(2)
    }

    @Test
    fun `one item alone reads 1 of 1`() {
        val board = DownloadBoard().queued("a", "A", 10L)

        assertThat(board.itemCount).isEqualTo(1)
        assertThat(board.currentPosition).isEqualTo(1)
    }

    @Test
    fun `the throttle lets one update through per interval, the first always`() {
        val throttle = ProgressThrottle(1_000L)

        assertThat(throttle.allow(5_000L)).isTrue()
        assertThat(throttle.allow(5_400L)).isFalse()
        assertThat(throttle.allow(5_999L)).isFalse()
        assertThat(throttle.allow(6_000L)).isTrue()
        assertThat(throttle.allow(6_100L)).isFalse()
    }
}
