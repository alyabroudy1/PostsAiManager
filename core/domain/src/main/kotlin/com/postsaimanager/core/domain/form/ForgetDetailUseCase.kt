package com.postsaimanager.core.domain.form

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.ProfileFactRepository
import javax.inject.Inject

/** Forgets one saved detail ("Forget this"). Profile columns are edited on the profile, not forgotten here. */
class ForgetDetailUseCase @Inject constructor(
    private val facts: ProfileFactRepository,
) {
    suspend operator fun invoke(profileId: String, key: String): PamResult<Unit> = facts.delete(profileId, key)
}
