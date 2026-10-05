package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DocumentTitlePolicyTest {

    private fun may(isUserTitle: Boolean = false, titleCode: String? = null) =
        DocumentTitlePolicy.modelTitleMayReplace(isUserTitle, titleCode)

    @Test
    fun `a default title is replaced by the model's`() {
        assertThat(may(titleCode = "scanned_pages")).isTrue()
    }

    @Test
    fun `a title that is already real words is never replaced, whoever wrote it`() {
        assertThat(may(titleCode = null)).isFalse()
    }

    @Test
    fun `a person's title is never replaced, default code or not`() {
        assertThat(may(isUserTitle = true)).isFalse()
        assertThat(may(isUserTitle = true, titleCode = "scanned_pages")).isFalse()
    }
}
