package com.postsaimanager.setup

import com.postsaimanager.core.domain.setup.DownloadActivity
import com.postsaimanager.core.download.DownloadBoard
import com.postsaimanager.core.download.DownloadNotificationCenter
import com.postsaimanager.core.model.DownloadSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Home's download banner reads the same board the one notification renders, so the two never disagree. */
@Singleton
class CenterDownloadActivity @Inject constructor(center: DownloadNotificationCenter) : DownloadActivity {
    override val summary: Flow<DownloadSummary?> = center.board.map(::summaryOf).distinctUntilChanged()
}

/** Null when nothing is running and nothing failed. A failed item with nothing else running is the "failed" banner. */
internal fun summaryOf(board: DownloadBoard): DownloadSummary? = when {
    board.isActive -> DownloadSummary(board.currentPosition, board.itemCount, board.overallPercent)
    board.hasFailed -> DownloadSummary(board.currentPosition, board.itemCount, board.overallPercent, failed = true)
    else -> null
}
