package com.postsaimanager.core.domain.form

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.ProfileFact
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.FakeProfileFactRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ProfileDetailUseCasesTest {

    private val profiles = FakeProfileRepository()
    private val facts = FakeProfileFactRepository()
    private val remember = RememberDetailUseCase(profiles, facts)

    private fun ahmad() = testProfile(id = "ahmad", name = "Ahmad", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.CHILD)

    @Test
    fun `a non-column key becomes a fact and takes its sensitivity from the registry`() = runTest {
        profiles.seed(ahmad())

        val result = remember("ahmad", "allergies", "  nuts ", FactSource.FORM_ANSWER, "doc-1")

        assertThat(result).isEqualTo(PamResult.Success(Unit))
        val fact = facts.facts("ahmad").single()
        assertThat(fact.key).isEqualTo("allergies")
        assertThat(fact.value).isEqualTo("nuts")
        assertThat(fact.sensitive).isTrue()
        assertThat(fact.source).isEqualTo(FactSource.FORM_ANSWER)
        assertThat(fact.sourceDocumentId).isEqualTo("doc-1")
    }

    @Test
    fun `remembering the same key again replaces the value`() = runTest {
        profiles.seed(ahmad())
        remember("ahmad", "school", "Grundschule A", FactSource.USER)
        remember("ahmad", "school", "Grundschule B", FactSource.USER)

        assertThat(facts.facts("ahmad").map { it.value }).containsExactly("Grundschule B")
    }

    @Test
    fun `a column key writes the profile and stores no fact`() = runTest {
        profiles.seed(ahmad())

        remember("ahmad", "phone", "0151 123", FactSource.USER)

        assertThat(profiles.updated.single().phone).isEqualTo("0151 123")
        assertThat(facts.facts("ahmad")).isEmpty()
    }

    @Test
    fun `an unknown key and a blank value are refused`() = runTest {
        profiles.seed(ahmad())

        assertThat(remember("ahmad", "shoe_size", "30", FactSource.USER)).isInstanceOf(PamResult.Error::class.java)
        val blank = remember("ahmad", "school", "   ", FactSource.USER)

        assertThat((blank as PamResult.Error).error).isInstanceOf(PamError.ValidationError::class.java)
        assertThat(facts.facts("ahmad")).isEmpty()
    }

    @Test
    fun `a column key of an unknown profile fails`() = runTest {
        assertThat(remember("ghost", "phone", "1", FactSource.USER)).isInstanceOf(PamResult.Error::class.java)
    }

    @Test
    fun `forget removes the fact`() = runTest {
        profiles.seed(ahmad())
        remember("ahmad", "school", "A", FactSource.USER)

        ForgetDetailUseCase(facts)("ahmad", "school")

        assertThat(facts.facts("ahmad")).isEmpty()
    }

    @Test
    fun `saved details come in registry order, unknown keys last`() = runTest {
        fun fact(key: String) = ProfileFact("id-$key", "ahmad", key, "v", FactSource.USER, createdAt = 1, updatedAt = 1)
        facts.seed(fact("school"), fact("zzz"), fact("allergies"), fact("birth_place"))

        val keys = ObserveSavedDetailsUseCase(facts)("ahmad").first().map { it.key }

        assertThat(keys).containsExactly("birth_place", "school", "allergies", "zzz").inOrder()
    }

    @Test
    fun `every profile column named by the registry is supported`() {
        val columns = FormDataKeys.ALL.mapNotNull(ProfileColumns::columnOf)
        assertThat(ProfileColumns.SUPPORTED).containsAtLeastElementsIn(columns)
    }

    @Test
    fun `the birth date key is owned by the profile, never stored as a fact, and must be ISO`() = runTest {
        profiles.seed(ahmad())

        assertThat(remember("ahmad", "birth_date", "12.03.2019", FactSource.USER)).isInstanceOf(PamResult.Error::class.java)
        assertThat(remember("ahmad", "birth_date", "2019-03-12", FactSource.USER)).isEqualTo(PamResult.Success(Unit))

        assertThat(profiles.updated.single().birthDate).isEqualTo("2019-03-12")
        assertThat(facts.facts("ahmad")).isEmpty()
    }

    @Test
    fun `birthDate is writable and read back, and must be an ISO date`() = runTest {
        val profile = ahmad()
        val written = ProfileColumns.write(profile, "birthDate", "2019-03-12", now = 5)!!
        assertThat(ProfileColumns.read(written, "birthDate")).isEqualTo("2019-03-12")
        assertThat(written.modifiedAt).isEqualTo(5)
        assertThat(ProfileColumns.write(profile, "nope", "x", 1)).isNull()
        assertThat(ProfileColumns.read(profile, "street")).isNull()
    }

    @Test
    fun `guardians of a child are Me and the partner, Me first`() = runTest {
        val me = testProfile(id = "me", name = "Mohammad", type = ProfileType.USER_SELF)
        val partner = testProfile(id = "wife", name = "Sara", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.PARTNER)
        profiles.seed(partner, ahmad(), me)

        assertThat(GuardiansOfUseCase(profiles)("ahmad").map { it.id }).containsExactly("me", "wife").inOrder()
    }

    @Test
    fun `a child without a partner has only Me, and a non-child has none`() = runTest {
        val me = testProfile(id = "me", name = "Mohammad", type = ProfileType.USER_SELF)
        val parent = testProfile(id = "mum", name = "Mum", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.PARENT)
        profiles.seed(me, ahmad(), parent)

        val guardians = GuardiansOfUseCase(profiles)
        assertThat(guardians("ahmad").map { it.id }).containsExactly("me")
        assertThat(guardians("mum")).isEmpty()
        assertThat(guardians("me")).isEmpty()
        assertThat(guardians("ghost")).isEmpty()
    }

    @Test
    fun `the fake and the real repository refuse a second Me`() = runTest {
        profiles.seed(testProfile(id = "me", type = ProfileType.USER_SELF))

        val result = profiles.createProfile(testProfile(id = "me2", type = ProfileType.USER_SELF))

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
    }
}
