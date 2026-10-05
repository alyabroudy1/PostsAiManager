package com.postsaimanager.feature.profiles

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.domain.form.ForgetDetailUseCase
import com.postsaimanager.core.domain.form.ObserveSavedDetailsUseCase
import com.postsaimanager.core.domain.form.RememberDetailUseCase
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.ProfileFact
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.FakeProfileFactRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class ProfileDetailViewModelTest {

    private val profiles = FakeProfileRepository()
    private val facts = FakeProfileFactRepository()

    private fun viewModel(id: String) = ProfileDetailViewModel(
        SavedStateHandle(mapOf(ProfileDetailViewModel.ARG_PROFILE_ID to id)),
        profiles,
        ObserveSavedDetailsUseCase(facts),
        RememberDetailUseCase(profiles, facts),
        ForgetDetailUseCase(facts),
    )

    private fun ahmad() = testProfile(id = "ahmad", name = "Ahmad", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.CHILD)

    @Test
    fun `an existing profile loads with its saved details`() = runTest {
        profiles.seed(ahmad())
        facts.seed(ProfileFact("f1", "ahmad", "allergies", "nuts", FactSource.USER, sensitive = true, createdAt = 1, updatedAt = 1))

        viewModel("ahmad").uiState.test {
            val state = expectMostRecentItem()
            assertThat(state.draft?.name).isEqualTo("Ahmad")
            assertThat(state.facts.map { it.key }).containsExactly("allergies")
            assertThat(state.isNew).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a missing profile reports not found`() = runTest {
        viewModel("ghost").uiState.test {
            val state = expectMostRecentItem()
            assertThat(state.notFound).isTrue()
            assertThat(state.draft).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `editing is a draft until save, then it is written`() = runTest {
        profiles.seed(ahmad())
        val vm = viewModel("ahmad")
        vm.uiState.test {
            vm.update { it.copy(name = "Ahmad M", birthDate = "2019-03-12", sensitive = true, phone = " 0151 ") }
            assertThat(profiles.updated).isEmpty()

            vm.save()

            val saved = profiles.updated.single()
            assertThat(saved.name).isEqualTo("Ahmad M")
            assertThat(saved.birthDate).isEqualTo("2019-03-12")
            assertThat(saved.sensitive).isTrue()
            assertThat(saved.phone).isEqualTo("0151")
            assertThat(saved.street).isNull()
            assertThat(expectMostRecentItem().finished).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a new person is created on save and not before, and a blank name cannot be saved`() = runTest {
        val vm = viewModel(ProfileDetailViewModel.NEW)
        vm.uiState.test {
            assertThat(expectMostRecentItem().canSave).isFalse()
            vm.save()
            assertThat(profiles.getProfiles().first()).isEmpty()

            vm.update { it.copy(name = "Sara") }
            vm.setRelationship(Relationship.PARTNER)
            vm.save()

            val created = profiles.getProfiles().first().single()
            assertThat(created.name).isEqualTo("Sara")
            assertThat(created.relationship).isEqualTo(Relationship.PARTNER)
            assertThat(created.type).isEqualTo(ProfileType.FAMILY_MEMBER)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `changing the type drops a relationship that no longer applies`() = runTest {
        profiles.seed(ahmad().copy(birthDate = "2019-03-12"))
        val vm = viewModel("ahmad")
        vm.uiState.test {
            vm.setType(ProfileType.AUTHORITY)

            val draft = expectMostRecentItem().draft!!
            assertThat(draft.relationship).isNull()
            assertThat(draft.birthDate).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Me is unavailable while another profile is Me`() = runTest {
        profiles.seed(testProfile(id = "me", type = ProfileType.USER_SELF), ahmad())

        viewModel("ahmad").uiState.test {
            assertThat(expectMostRecentItem().selfTaken).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel("me").uiState.test {
            assertThat(expectMostRecentItem().selfTaken).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed save is reported and does not finish`() = runTest {
        profiles.seed(ahmad())
        val vm = viewModel("ahmad")
        vm.uiState.test {
            profiles.failWith = PamError.Unknown(cause = RuntimeException("disk"))
            vm.save()

            assertThat(vm.message.value).isNotNull()
            assertThat(expectMostRecentItem().finished).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `adding a detail saves a fact, editing replaces it, deleting offers an undo that restores it`() = runTest {
        profiles.seed(ahmad())
        val vm = viewModel("ahmad")
        vm.uiState.test {
            vm.saveDetail("allergies", "nuts")
            vm.saveDetail("allergies", "nuts and milk")
            val fact = facts.facts("ahmad").single()
            assertThat(fact.value).isEqualTo("nuts and milk")
            assertThat(fact.sensitive).isTrue()

            vm.deleteDetail(fact)
            assertThat(facts.facts("ahmad")).isEmpty()
            assertThat(vm.removed.value).isEqualTo(fact)

            vm.undoDelete()
            assertThat(facts.facts("ahmad").single().value).isEqualTo("nuts and milk")
            assertThat(vm.removed.value).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a blank detail is refused with a message`() = runTest {
        profiles.seed(ahmad())
        val vm = viewModel("ahmad")

        vm.saveDetail("school", "  ")

        assertThat(vm.message.value).isNotNull()
        assertThat(facts.facts("ahmad")).isEmpty()
    }

    @Test
    fun `the picker offers registry keys that are neither profile fields nor already saved`() = runTest {
        profiles.seed(ahmad())
        val vm = viewModel("ahmad")
        val saved = listOf(ProfileFact("f", "ahmad", "school", "A", FactSource.USER, createdAt = 1, updatedAt = 1))

        val ids = vm.availableKeys(saved).map { it.id }

        assertThat(ids).contains("allergies")
        assertThat(ids).doesNotContain("school")
        assertThat(ids).containsNoneOf("phone", "email", "street", "city", "postcode", "full_name", "birth_date")
    }
}
