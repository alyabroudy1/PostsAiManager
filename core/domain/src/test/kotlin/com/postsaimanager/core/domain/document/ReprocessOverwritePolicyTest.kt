package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.SummarySource
import org.junit.jupiter.api.Test

class ReprocessOverwritePolicyTest {

    private fun doc(family: FamilySource = FamilySource.MODEL, summary: SummarySource? = null) = Document(
        id = "d", title = "t", sourceType = SourceType.CAMERA, createdAt = 1, modifiedAt = 1,
        familySource = family, summarySource = summary,
    )

    @Test
    fun `the family is overwritten only while the model chose it`() {
        assertThat(ReprocessOverwritePolicy.mayOverwriteFamily(doc(family = FamilySource.MODEL))).isTrue()
        assertThat(ReprocessOverwritePolicy.mayOverwriteFamily(doc(family = FamilySource.USER))).isFalse()
    }

    @Test
    fun `the summary is overwritten unless a person wrote it`() {
        assertThat(ReprocessOverwritePolicy.mayOverwriteSummary(doc(summary = null))).isTrue()
        assertThat(ReprocessOverwritePolicy.mayOverwriteSummary(doc(summary = SummarySource.MODEL))).isTrue()
        assertThat(ReprocessOverwritePolicy.mayOverwriteSummary(doc(summary = SummarySource.TEMPLATE))).isTrue()
        assertThat(ReprocessOverwritePolicy.mayOverwriteSummary(doc(summary = SummarySource.USER))).isFalse()
    }
}
