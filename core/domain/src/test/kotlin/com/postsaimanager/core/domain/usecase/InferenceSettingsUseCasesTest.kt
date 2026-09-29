package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceOverrides
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeInferenceSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for [ObserveInferenceSettingsUseCase], [UpdateInferenceSettingUseCase] and
 * [ResetInferenceSettingsUseCase] — the key-dispatched write path a schema-driven UI needs,
 * and that the effective config the UI reads actually reflects a persisted override.
 */
class InferenceSettingsUseCasesTest {

    private val repository = FakeInferenceSettingsRepository()
    private val models = FakeActiveModelProvider()

    @Test
    fun `observe reflects the current overrides`() = runTest {
        // effectiveConfig itself comes from ActiveModelProvider.activeModelConfig(), which
        // is where CatalogActiveModelProvider actually applies overrides in production —
        // FakeActiveModelProvider deliberately stays override-agnostic, so this only checks
        // that the use case forwards what the repository holds.
        val update = UpdateInferenceSettingUseCase(repository)
        val observe = ObserveInferenceSettingsUseCase(models, repository)

        update("threads", 2)

        val state = observe().first()
        assertThat(state.overrides.threads).isEqualTo(2)
        assertThat(state.schema).isEqualTo(models.activeModelSchema())
    }

    @Test
    fun `update threads writes an Int override`() = runTest {
        UpdateInferenceSettingUseCase(repository)("threads", 3)
        assertThat(repository.overrides.first().threads).isEqualTo(3)
    }

    @Test
    fun `update contextTokens parses the Choice's String value`() = runTest {
        UpdateInferenceSettingUseCase(repository)("contextTokens", "2048")
        assertThat(repository.overrides.first().contextTokens).isEqualTo(2048)
    }

    @Test
    fun `update accelerator parses the Choice's String value`() = runTest {
        UpdateInferenceSettingUseCase(repository)("accelerator", "GPU")
        assertThat(repository.overrides.first().accelerator).isEqualTo(Accelerator.GPU)
    }

    @Test
    fun `update temperature writes a Float override`() = runTest {
        UpdateInferenceSettingUseCase(repository)("temperature", 0.42f)
        assertThat(repository.overrides.first().temperature).isEqualTo(0.42f)
    }

    @Test
    fun `update flashAttention writes a Boolean override`() = runTest {
        UpdateInferenceSettingUseCase(repository)("flashAttention", true)
        assertThat(repository.overrides.first().flashAttention).isTrue()
    }

    @Test
    fun `update with an unknown key is a no-op`() = runTest {
        UpdateInferenceSettingUseCase(repository)("threads", 5)
        UpdateInferenceSettingUseCase(repository)("nonsense", "whatever")
        assertThat(repository.overrides.first().threads).isEqualTo(5)
    }

    @Test
    fun `reset clears every override`() = runTest {
        val update = UpdateInferenceSettingUseCase(repository)
        update("threads", 1)
        update("flashAttention", true)

        ResetInferenceSettingsUseCase(repository)()

        assertThat(repository.overrides.first()).isEqualTo(InferenceOverrides.NONE)
    }
}
