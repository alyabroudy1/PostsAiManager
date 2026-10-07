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
import com.postsaimanager.core.domain.applock.ExternalFlowGuard
import com.postsaimanager.core.domain.contacts.DeleteContactUseCase
import com.postsaimanager.core.domain.contacts.MergeContactsUseCase
import com.postsaimanager.core.domain.contacts.MoveContactUseCase
import com.postsaimanager.core.domain.contacts.ObserveOrganisationContactsUseCase
import com.postsaimanager.core.domain.contacts.SetContactActiveUseCase
import com.postsaimanager.core.domain.contacts.SetHouseholdRoleUseCase
import com.postsaimanager.core.domain.contacts.UpdateContactUseCase
import io.mockk.mockk
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.FakeContactRepository
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
    private val contacts = FakeContactRepository()

    private fun viewModel(id: String, role: String? = null) = ProfileDetailViewModel(
        SavedStateHandle(listOfNotNull(ProfileDetailViewModel.ARG_PROFILE_ID to id, role?.let { ProfileDetailViewModel.ARG_ROLE to it }).toMap()),
        profiles,
        SetHouseholdRoleUseCase(profiles),
        ObserveOrganisationContactsUseCase(contacts),
        ObserveSavedDetailsUseCase(facts),
        RememberDetailUseCase(profiles, facts),
        ForgetDetailUseCase(facts),
        UpdateContactUseCase(contacts),
        SetContactActiveUseCase(contacts),
        MergeContactsUseCase(contacts),
        MoveContactUseCase(contacts, profiles),
        DeleteContactUseCase(contacts),
        guard,
    )

    private val guard = mockk<ExternalFlowGuard>(relaxed = true)

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
    fun `a new person opened from the household card is Me, and a role name this build does not know falls back to a member`() = runTest {
        val vm = viewModel(ProfileDetailViewModel.NEW, role = "SELF")
        vm.uiState.test {
            assertThat(expectMostRecentItem().draft?.householdRole).isEqualTo(HouseholdRole.SELF)
            vm.update { it.copy(name = "Erika Mustermann") }
            vm.save()

            val created = profiles.getProfiles().first().single()
            assertThat(created.isSelf).isTrue()
            assertThat(created.isManaged).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(ProfileDetailViewModel.startingRole("")).isEqualTo(HouseholdRole.MEMBER)
        assertThat(ProfileDetailViewModel.startingRole(null)).isEqualTo(HouseholdRole.MEMBER)
        assertThat(ProfileDetailViewModel.startingRole("GUARDIAN")).isEqualTo(HouseholdRole.MEMBER)
        assertThat(ProfileDetailViewModel.startingRole("SELF")).isEqualTo(HouseholdRole.SELF)
    }

    @Test
    fun `changing the kind to organisation drops the role, relationship and birth date`() = runTest {
        profiles.seed(ahmad().copy(birthDate = "2019-03-12"))
        val vm = viewModel("ahmad")
        vm.uiState.test {
            vm.setKind(ProfileKind.ORGANISATION)

            val draft = expectMostRecentItem().draft!!
            assertThat(draft.householdRole).isNull()
            assertThat(draft.relationship).isNull()
            assertThat(draft.birthDate).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a changed household role is applied on save through the use case, not before`() = runTest {
        profiles.seed(ahmad())
        val vm = viewModel("ahmad")
        vm.uiState.test {
            vm.setRole(null)
            assertThat(profiles.getProfileById("ahmad").let { (it as com.postsaimanager.core.common.result.PamResult.Success).data.householdRole })
                .isEqualTo(HouseholdRole.MEMBER)

            vm.save()

            val stored = (profiles.getProfileById("ahmad") as com.postsaimanager.core.common.result.PamResult.Success).data
            assertThat(stored.householdRole).isNull()
            assertThat(stored.relationship).isNull()
            assertThat(expectMostRecentItem().finished).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Me cannot be cleared from the editor, the save reports it`() = runTest {
        profiles.seed(testProfile(id = "me", name = "Mo", type = ProfileType.USER_SELF))
        val vm = viewModel("me")
        vm.uiState.test {
            assertThat(expectMostRecentItem().selfLocked).isTrue()
            vm.setRole(null)
            vm.save()

            assertThat(vm.message.value).isNotNull()
            assertThat(expectMostRecentItem().finished).isFalse()
            val stored = (profiles.getProfileById("me") as com.postsaimanager.core.common.result.PamResult.Success).data
            assertThat(stored.isSelf).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an organisation shows its contacts, current first`() = runTest {
        profiles.seed(testProfile(id = "jc", name = "Jobcenter", type = ProfileType.AUTHORITY))
        contacts.seed(
            ContactPerson("c1", "jc", "Nadine Beispiel", firstSeen = 1, lastSeen = 10),
            ContactPerson("c2", "jc", "Frau Müller", firstSeen = 20, lastSeen = 30),
        )

        viewModel("jc").uiState.test {
            val found = expectMostRecentItem().contacts
            assertThat(found.current?.name).isEqualTo("Frau Müller")
            assertThat(found.earlier.map { it.name }).containsExactly("Nadine Beispiel")
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun seedJobcenterWithTwoContacts() {
        profiles.seed(
            testProfile(id = "jc", name = "Jobcenter Musterstadt", type = ProfileType.AUTHORITY),
            testProfile(id = "jc2", name = "Jobcenter Nordstadt", type = ProfileType.AUTHORITY),
        )
        contacts.seed(
            ContactPerson("c1", "jc", "Nadine Beispiel", firstSeen = 1, lastSeen = 10),
            ContactPerson("c2", "jc", "Frau Müller", firstSeen = 20, lastSeen = 30),
        )
    }

    @Test
    fun `editing a contact saves what was typed`() = runTest {
        seedJobcenterWithTwoContacts()
        val vm = viewModel("jc")

        vm.saveContact(contacts.getContact("c2").let { (it as com.postsaimanager.core.common.result.PamResult.Success).data }.copy(phone = "030 222", title = "Teamleiterin"))

        vm.uiState.test {
            val current = expectMostRecentItem().contacts.current
            assertThat(current?.phone).isEqualTo("030 222")
            assertThat(current?.title).isEqualTo("Teamleiterin")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `marking the current contact no longer responsible makes the earlier one current`() = runTest {
        seedJobcenterWithTwoContacts()
        val vm = viewModel("jc")

        vm.setContactActive("c2", false)

        vm.uiState.test {
            val found = expectMostRecentItem().contacts
            assertThat(found.current?.name).isEqualTo("Nadine Beispiel")
            assertThat(found.earlier.single().active).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `merging, moving and deleting go through the contact use cases`() = runTest {
        seedJobcenterWithTwoContacts()
        contacts.seed(ContactPerson("c3", "jc", "N. Beispiel", firstSeen = 30, lastSeen = 40))
        contacts.linkContactToDocument("c3", "d3")
        val vm = viewModel("jc")

        vm.mergeContact(keepId = "c1", mergedId = "c3")
        vm.moveContactTo("c2", "jc2")
        vm.removeContact("c1")

        vm.uiState.test {
            val state = expectMostRecentItem()
            assertThat(state.contacts.isEmpty).isTrue()
            assertThat(state.otherOrganisations.map { it.id }).containsExactly("jc2")
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(contacts.observeContacts("jc2").first().map { it.name }).containsExactly("Frau Müller")
        assertThat(contacts.isRemovedFromDocument("d3", "Nadine Beispiel")).isTrue()
    }

    @Test
    fun `a refused change is reported`() = runTest {
        seedJobcenterWithTwoContacts()
        profiles.seed(testProfile(id = "anna", name = "Anna", type = ProfileType.PERSON))
        val vm = viewModel("jc")

        vm.moveContactTo("c1", "anna")

        assertThat(vm.message.value).isNotNull()
    }

    @Test
    fun `the contact a letter chip opened the page for is the focus`() = runTest {
        seedJobcenterWithTwoContacts()
        val vm = ProfileDetailViewModel(
            SavedStateHandle(mapOf(ProfileDetailViewModel.ARG_PROFILE_ID to "jc", ProfileDetailViewModel.ARG_CONTACT_ID to "c1")),
            profiles, SetHouseholdRoleUseCase(profiles), ObserveOrganisationContactsUseCase(contacts), ObserveSavedDetailsUseCase(facts),
            RememberDetailUseCase(profiles, facts), ForgetDetailUseCase(facts), UpdateContactUseCase(contacts), SetContactActiveUseCase(contacts),
            MergeContactsUseCase(contacts), MoveContactUseCase(contacts, profiles), DeleteContactUseCase(contacts), guard,
        )

        vm.uiState.test {
            assertThat(expectMostRecentItem().focusContactId).isEqualTo("c1")
            cancelAndIgnoreRemainingEvents()
        }
        viewModel("jc").uiState.test {
            assertThat(expectMostRecentItem().focusContactId).isNull()
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
