package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ModelDocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.QuestionnaireInterpreter
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import org.junit.jupiter.api.Test

class ModelProfileTest {

    private val factory = ProfileInterpreterFactory(FakeAiEngine(), FakePromptSession())

    @Test
    fun `the factory builds the strategy the model's profile names`() {
        fun profile(s: InterpreterStrategy) = ModelProfile("m", contextTokens = 4096, strategy = s)
        assertThat(factory.create(4096, profile(InterpreterStrategy.SINGLE))).isInstanceOf(ModelDocumentInterpreter::class.java)
        assertThat(factory.create(4096, profile(InterpreterStrategy.QUESTIONNAIRE))).isInstanceOf(QuestionnaireInterpreter::class.java)
        assertThat(factory.create(4096, profile(InterpreterStrategy.ZONES))).isInstanceOf(ZoneInterpreter::class.java)
        assertThat(factory.create(4096, profile(InterpreterStrategy.ZONES_SCORING))).isInstanceOf(ZoneScoringInterpreter::class.java)
    }

    @Test
    fun `an unknown model gets the strategy that needs no measurement`() {
        assertThat(ModelProfiles.of("something-side-loaded")).isEqualTo(ModelProfiles.FALLBACK)
        assertThat(ModelProfiles.of(null).strategy).isEqualTo(InterpreterStrategy.SINGLE)
        assertThat(factory.create(4096, "something-side-loaded")).isInstanceOf(ModelDocumentInterpreter::class.java)
    }

    @Test
    fun `every profile is for a distinct catalogue model`() {
        assertThat(ModelProfiles.ALL.map { it.modelId }.toSet()).hasSize(ModelProfiles.ALL.size)
        assertThat(ModelProfiles.of(ModelProfiles.QWEN35_08B.modelId)).isSameInstanceAs(ModelProfiles.QWEN35_08B)
    }
}
