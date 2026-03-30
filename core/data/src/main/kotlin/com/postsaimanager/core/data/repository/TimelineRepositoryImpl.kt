package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.TimelineDao
import com.postsaimanager.core.data.database.entity.TimelineEventEntity
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.model.TimelineEvent
import com.postsaimanager.core.model.TimelineEventType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class TimelineRepositoryImpl @Inject constructor(
    private val timelineDao: TimelineDao,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : TimelineRepository {

    override fun getTimelineForDocument(documentId: String): Flow<List<TimelineEvent>> =
        timelineDao.observeForDocument(documentId).map { entities ->
            entities.map { entity ->
                TimelineEvent(
                    id = entity.id,
                    documentId = entity.documentId,
                    eventType = TimelineEventType.valueOf(entity.eventType),
                    title = entity.title,
                    description = entity.description,
                    data = entity.data,
                    referenceId = entity.referenceId,
                    referenceType = entity.referenceType,
                    createdAt = entity.createdAt,
                )
            }
        }.flowOn(ioDispatcher)

    override suspend fun recordEvent(event: TimelineEvent): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                timelineDao.insert(
                    TimelineEventEntity(
                        id = event.id,
                        documentId = event.documentId,
                        eventType = event.eventType.name,
                        title = event.title,
                        description = event.description,
                        data = event.data,
                        referenceId = event.referenceId,
                        referenceType = event.referenceType,
                        createdAt = event.createdAt,
                    )
                )
                PamResult.Success(Unit)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }
}
