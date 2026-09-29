package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DocumentTitlePolicyTest {

    private fun may(isUserTitle: Boolean = false, titleCode: String? = null, modelHasRead: Boolean = false) =
        DocumentTitlePolicy.modelTitleMayReplace(isUserTitle, titleCode, modelHasRead)

    @Test
    fun `a default title is replaced by the model's`() {
        assertThat(may(titleCode = "scanned_pages")).isTrue()
    }

    @Test
    fun `the first model reading replaces whatever title there is`() {
        assertThat(may(titleCode = null, modelHasRead = false)).isTrue()
    }

    @Test
    fun `a reprocess never renames a document that already has a model title`() {
        assertThat(may(titleCode = null, modelHasRead = true)).isFalse()
    }

    @Test
    fun `a person's title is never replaced, default code or not`() {
        assertThat(may(isUserTitle = true)).isFalse()
        assertThat(may(isUserTitle = true, titleCode = "scanned_pages")).isFalse()
        assertThat(may(isUserTitle = true, modelHasRead = true)).isFalse()
    }

    @Test
    fun `a default title still in place is replaced even after an earlier model reading`() {
        // The earlier reading produced no usable title, so the document still carries its default.
        assertThat(may(titleCode = "scanned_pages", modelHasRead = true)).isTrue()
    }
}
