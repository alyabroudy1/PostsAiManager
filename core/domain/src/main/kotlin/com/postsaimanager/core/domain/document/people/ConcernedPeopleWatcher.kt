package com.postsaimanager.core.domain.document.people

import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.Profile
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The one owner of "when is the question of who a document concerns asked": started once with the application.
 *  - Whenever a model is installed (and at start when one is), the documents still "not asked yet" are queued
 *    ([BackfillConcernedPeopleUseCase]): the one-off backfill, which runs once per document because the check writes its answer.
 *  - Whenever a managed profile is added or renamed, however it came about (the profile editor, the entity linker, onboarding), the
 *    documents whose text mentions its name are set back to "not asked yet" and queued ([QueueConcernedPeopleCheckUseCase]). It diffs
 *    the managed profiles by id and name, so nothing that creates or renames a profile has to remember to call it. The profiles
 *    present at start are the baseline: they were covered by the backfill.
 */
class ConcernedPeopleWatcher @Inject constructor(
    private val profiles: ProfileRepository,
    private val installedModels: InstalledModelsRepository,
    private val queueCheck: QueueConcernedPeopleCheckUseCase,
    private val backfill: BackfillConcernedPeopleUseCase,
) {

    /** Never returns; runs in the application scope. */
    suspend fun watch() = coroutineScope {
        launch {
            installedModels.installed.map { it.isNotEmpty() }.distinctUntilChanged().collect { hasModel ->
                if (hasModel) backfill()
            }
        }
        launch {
            var known: Map<String, String>? = null
            profiles.getProfiles().map { all -> all.filter { it.isManaged } }.collect { managed ->
                val before = known
                known = managed.associate { it.id to it.name }
                if (before != null) changed(before, managed).forEach { queueCheck(it) }
            }
        }
    }

    internal companion object {
        /** The managed profiles that are new, or whose name differs, compared with [before] (id to name). */
        fun changed(before: Map<String, String>, now: List<Profile>): List<Profile> = now.filter { before[it.id] != it.name }
    }
}
