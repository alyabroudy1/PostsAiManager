package com.postsaimanager.core.domain.contacts

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SetHouseholdRoleUseCaseTest {

    private val profiles = FakeProfileRepository()
    private val setRole = SetHouseholdRoleUseCase(profiles)

    private suspend fun stored(id: String): Profile = (profiles.getProfileById(id) as PamResult.Success).data

    @Test
    fun `a person joins the household as a member with a relationship`() = runTest {
        profiles.seed(testProfile(id = "maria", type = ProfileType.PERSON))

        val result = setRole("maria", HouseholdRole.MEMBER, Relationship.PARTNER)

        assertThat(result).isEqualTo(PamResult.Success(Unit))
        assertThat(stored("maria").householdRole).isEqualTo(HouseholdRole.MEMBER)
        assertThat(stored("maria").relationship).isEqualTo(Relationship.PARTNER)
        assertThat(stored("maria").isManaged).isTrue()
    }

    @Test
    fun `a member can leave the household and the relationship goes with it`() = runTest {
        profiles.seed(testProfile(id = "maria", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.PARTNER))

        setRole("maria", null)

        assertThat(stored("maria").householdRole).isNull()
        assertThat(stored("maria").relationship).isNull()
        assertThat(stored("maria").isManaged).isFalse()
    }

    @Test
    fun `Me can be set once, a second Me is refused`() = runTest {
        profiles.seed(testProfile(id = "me", type = ProfileType.PERSON), testProfile(id = "other", type = ProfileType.PERSON))

        assertThat(setRole("me", HouseholdRole.SELF)).isEqualTo(PamResult.Success(Unit))
        val second = setRole("other", HouseholdRole.SELF)

        assertThat((second as PamResult.Error).error).isInstanceOf(PamError.ValidationError::class.java)
        assertThat(stored("other").householdRole).isNull()
    }

    @Test
    fun `Me cannot be cleared or turned into a member`() = runTest {
        profiles.seed(testProfile(id = "me", type = ProfileType.USER_SELF))

        assertThat(setRole("me", null)).isInstanceOf(PamResult.Error::class.java)
        assertThat(setRole("me", HouseholdRole.MEMBER, Relationship.PARTNER)).isInstanceOf(PamResult.Error::class.java)
        assertThat(stored("me").isSelf).isTrue()
    }

    @Test
    fun `setting Me again on Me changes nothing`() = runTest {
        profiles.seed(testProfile(id = "me", type = ProfileType.USER_SELF))

        assertThat(setRole("me", HouseholdRole.SELF)).isEqualTo(PamResult.Success(Unit))
        assertThat(profiles.updated).isEmpty()
    }

    @Test
    fun `an organisation cannot join the household`() = runTest {
        profiles.seed(testProfile(id = "jc", type = ProfileType.AUTHORITY))

        val result = setRole("jc", HouseholdRole.MEMBER, Relationship.OTHER)

        assertThat((result as PamResult.Error).error).isInstanceOf(PamError.ValidationError::class.java)
        assertThat(stored("jc").householdRole).isNull()
    }

    @Test
    fun `a relationship is dropped for Me`() = runTest {
        profiles.seed(testProfile(id = "me", type = ProfileType.PERSON, relationship = Relationship.OTHER))

        setRole("me", HouseholdRole.SELF, Relationship.PARTNER)

        assertThat(stored("me").relationship).isNull()
    }

    @Test
    fun `an unknown profile is an error`() = runTest {
        assertThat(setRole("ghost", HouseholdRole.MEMBER)).isInstanceOf(PamResult.Error::class.java)
    }
}
