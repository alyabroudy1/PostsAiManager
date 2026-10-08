package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.BaselineScores
import com.postsaimanager.core.domain.organisation.DetailOwnerQuestion
import com.postsaimanager.core.domain.organisation.DetailQuestion

/**
 * A scripted [DetailOwnerQuestion]: says "yes" (well above the made-up party) for the values in [organisationValues] or
 * [contactValues], "no" for every other, and fails with an error when [failing] (no model).
 */
class FakeDetailOwnerQuestion : DetailOwnerQuestion {
    var organisationValues: Set<String> = emptySet()
    var contactValues: Set<String> = emptySet()
    var failing: Boolean = false

    /** The values asked about, in order (both questions). */
    val asked = mutableListOf<String>()

    override suspend fun scoreOrganisation(question: DetailQuestion): PamResult<BaselineScores> {
        asked += question.value
        return answer(question.value in organisationValues)
    }

    override suspend fun scoreContact(question: DetailQuestion): PamResult<BaselineScores> = answer(question.value in contactValues)

    private fun answer(yes: Boolean): PamResult<BaselineScores> =
        if (failing) PamResult.Error(PamError.ValidationError("model", "no model")) else PamResult.Success(BaselineScores(listOf(if (yes) 5.0 else -5.0), 0.0))
}
