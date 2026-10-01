package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EntityNamesTest {

    @Test
    fun `whitespace and casing do not make two names different`() {
        assertThat(normaliseEntityName("  Jobcenter Berlin ")).isEqualTo(normaliseEntityName("jobcenter berlin"))
        assertThat(normaliseEntityName("Erika")).isNotEqualTo(normaliseEntityName("Erik"))
    }
}
