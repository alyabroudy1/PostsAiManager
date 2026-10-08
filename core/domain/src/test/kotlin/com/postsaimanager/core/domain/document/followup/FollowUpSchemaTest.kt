package com.postsaimanager.core.domain.document.followup

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FollowUpSchemaTest {

    @Test
    @DisplayName("no follow-up schema uses a keyword the engine's schema compiler does not implement (uniqueItems made it refuse the whole schema on the device)")
    fun `only implemented keywords`() {
        val ask = FollowUpPrompts.concernedPerson("Maria Mustermann", null, false)

        assertThat(ask.schema).doesNotContain("uniqueItems")
    }
}
