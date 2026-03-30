package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.TimelineEvent
import com.postsaimanager.core.model.TimelineEventType
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for timeline event tracking.
 */
interface TimelineRepository {
    fun getTimelineForDocument(documentId: String): Flow<List<TimelineEvent>>
    fun observeEvents(documentId: String): Flow<List<TimelineEvent>> = getTimelineForDocument(documentId)
    suspend fun recordEvent(event: TimelineEvent): PamResult<Unit>
}
