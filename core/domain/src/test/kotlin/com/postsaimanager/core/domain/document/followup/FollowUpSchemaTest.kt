package com.postsaimanager.core.domain.document.followup

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.form.SubjectCandidate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FollowUpSchemaTest {

    @Test
    @DisplayName("no follow-up schema uses a keyword the engine's schema compiler does not implement (uniqueItems made it refuse the whole schema on the device)")
    fun `only implemented keywords`() {
        val ask = FollowUpPrompts.concerned(listOf(SubjectCandidate("maria", "Maria Mustermann")), "Zed Zeta")

        assertThat(ask.multiple).isTrue()
        assertThat(ask.schema).doesNotContain("uniqueItems")
    }
}
