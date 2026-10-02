package com.postsaimanager.feature.profiles

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.designsystem.FormKeyLabels
import com.postsaimanager.core.domain.form.FormDataKeys
import org.junit.jupiter.api.Test

class FormKeyLabelsTest {

    @Test
    fun `every key of the registry has a label, and no label is left without a key`() {
        assertThat(FormKeyLabels.keyIds).containsExactlyElementsIn(FormDataKeys.ALL.map { it.id })
        FormDataKeys.ALL.forEach { assertThat(FormKeyLabels.of(it.id)).isNotNull() }
        assertThat(FormKeyLabels.of("shoe_size")).isNull()
    }
}
