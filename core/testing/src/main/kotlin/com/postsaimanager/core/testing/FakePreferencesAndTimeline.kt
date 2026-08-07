package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.model.AppTheme
import com.postsaimanager.core.model.TimelineEvent
import com.postsaimanager.core.model.TimelineEventType
import com.postsaimanager.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [UserPreferencesRepository]. Each setter mutates the emitted state. */
class FakeUserPreferencesRepository(
    initial: UserPreferences = UserPreferences(),
) : UserPreferencesRepository {

    private val prefs = MutableStateFlow(initial)

    /** When set, every setter fails and the stored value is left untouched. */
    var failWith: PamError? = null

    val current: UserPreferences get() = prefs.value

    override fun getUserPreferences(): Flow<UserPreferences> = prefs

    private fun update(block: (UserPreferences) -> UserPreferences): PamResult<Unit> {
        failWith?.let { return PamResult.Error(it) }
        prefs.value = block(prefs.value)
        return PamResult.Success(Unit)
    }

    override suspend fun setTheme(theme: AppTheme) = update { it.copy(theme = theme) }

    override suspend fun setAutoProcess(enabled: Boolean) =
        update { it.copy(autoProcessAfterScan = enabled) }

    override suspend fun setDefaultLanguage(language: String) =
        update { it.copy(defaultLanguage = language) }

    override suspend fun setNotificationsEnabled(enabled: Boolean) =
        update { it.copy(notificationsEnabled = enabled) }

    override suspend fun setAiModelId(modelId: String?) =
        update { it.copy(selectedAiModelId = modelId) }

    override suspend fun setBiometricEnabled(enabled: Boolean) =
        update { it.copy(biometricEnabled = enabled) }
}

/** In-memory [TimelineRepository]. Recorded events are inspectable via [recorded]. */
class FakeTimelineRepository : TimelineRepository {

    private val events = MutableStateFlow<List<TimelineEvent>>(emptyList())

    /** When set, [recordEvent] fails and nothing is stored. */
    var failWith: PamError? = null

    val recorded: List<TimelineEvent> get() = events.value

    fun seed(vararg items: TimelineEvent) {
        events.value = events.value + items
    }

    override fun getTimelineForDocument(documentId: String): Flow<List<TimelineEvent>> =
        events.map { list -> list.filter { it.documentId == documentId } }

    override suspend fun recordEvent(event: TimelineEvent): PamResult<Unit> {
        failWith?.let { return PamResult.Error(it) }
        events.value = events.value + event
        return PamResult.Success(Unit)
    }
}

/** Convenience builder for test timeline events. */
fun testTimelineEvent(
    id: String = "t1",
    documentId: String = "d1",
    eventType: TimelineEventType = TimelineEventType.DOCUMENT_SCANNED,
    title: String = "Scanned",
    createdAt: Long = 0L,
) = TimelineEvent(
    id = id,
    documentId = documentId,
    eventType = eventType,
    title = title,
    createdAt = createdAt,
)
