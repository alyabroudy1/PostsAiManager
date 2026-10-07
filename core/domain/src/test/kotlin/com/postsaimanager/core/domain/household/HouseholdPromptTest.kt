package com.postsaimanager.core.domain.household

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.FakeUserPreferencesRepository
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class HouseholdPromptTest {

    private val profiles = FakeProfileRepository()
    private val documents = FakeDocumentRepository()
    private val preferences = FakeUserPreferencesRepository()
    private val observe = ObserveHouseholdPromptUseCase(profiles, documents, preferences)

    @Test
    fun `with letters but no Me the card asks to add Me`() = runTest {
        documents.seed(testDocument())
        profiles.seed(testProfile(id = "org", type = ProfileType.AUTHORITY))

        assertThat(observe().first()).isEqualTo(HouseholdRole.SELF)
    }

    @Test
    fun `an empty app has nothing to tag, so no card`() = runTest {
        assertThat(observe().first()).isNull()
    }

    @Test
    fun `once Me exists the card is gone, family members or not`() = runTest {
        documents.seed(testDocument())
        observe().test {
            assertThat(awaitItem()).isEqualTo(HouseholdRole.SELF)
            profiles.seed(testProfile(id = "me", type = ProfileType.USER_SELF))
            assertThat(awaitItem()).isNull()
        }
    }

    @Test
    fun `a family member alone does not hide the card, because no letter can be tagged You`() = runTest {
        documents.seed(testDocument())
        profiles.seed(testProfile(id = "maria", type = ProfileType.FAMILY_MEMBER))

        assertThat(observe().first()).isEqualTo(HouseholdRole.SELF)
    }

    @Test
    fun `dismissing hides the card for good`() = runTest {
        documents.seed(testDocument())
        observe().test {
            assertThat(awaitItem()).isEqualTo(HouseholdRole.SELF)
            DismissHouseholdPromptUseCase(preferences)()
            assertThat(awaitItem()).isNull()
        }
        assertThat(preferences.current.householdPromptDismissed).isTrue()
    }
}
