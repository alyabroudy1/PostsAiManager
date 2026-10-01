package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for [ProfileMatcher.findBestMatch], the scoring behind the entity linker's decision whether a recognised organisation or
 * person is already on file. A false positive files a letter under the wrong correspondent.
 */
class ProfileMatcherTest {

    private val repo = FakeProfileRepository()
    private val matcher = ProfileMatcher(repo)

    @Test
    fun `identical organisation scores as an exact match`() = runTest {
        repo.similarProfilesOverride = listOf(testProfile(name = "Jobcenter Berlin", organization = "Jobcenter Berlin"))

        val (profile, confidence) = matcher.findBestMatch(null, "Jobcenter Berlin")

        assertThat(profile).isNotNull()
        assertThat(confidence).isAtLeast(ProfileMatcher.EXACT_MATCH_CONFIDENCE)
    }

    @Test
    fun `no candidates gives no profile and zero confidence`() = runTest {
        repo.similarProfilesOverride = emptyList()

        val (profile, confidence) = matcher.findBestMatch(null, "Unbekannte GmbH")

        assertThat(profile).isNull()
        assertThat(confidence).isEqualTo(0f)
    }

    @Test
    fun `a partial name overlap is a possible match below the exact bar`() = runTest {
        repo.similarProfilesOverride = listOf(testProfile(name = "Max Mustermann", organization = null))

        val (profile, confidence) = matcher.findBestMatch("Max", null)

        assertThat(profile).isNotNull()
        assertThat(confidence).isGreaterThan(0f)
        assertThat(confidence).isLessThan(ProfileMatcher.EXACT_MATCH_CONFIDENCE)
    }

    @Test
    fun `a completely unrelated candidate scores zero`() = runTest {
        repo.similarProfilesOverride = listOf(testProfile(name = "Allianz Versicherung", organization = "Allianz"))

        val (_, confidence) = matcher.findBestMatch(null, "Stadtwerke München")

        assertThat(confidence).isEqualTo(0f)
    }

    @Test
    fun `the best-scoring candidate wins regardless of order`() = runTest {
        val weak = testProfile(id = "weak", name = "Jobcenter Hamburg", organization = "Jobcenter Hamburg")
        val perfect = testProfile(id = "perfect", name = "Jobcenter Berlin", organization = "Jobcenter Berlin")
        repo.similarProfilesOverride = listOf(weak, perfect)

        val (profile, confidence) = matcher.findBestMatch(null, "Jobcenter Berlin")

        assertThat(profile?.id).isEqualTo("perfect")
        assertThat(confidence).isAtLeast(ProfileMatcher.EXACT_MATCH_CONFIDENCE)
    }

    @Test
    fun `a bare legal form does not match an organisation that carries it`() = runTest {
        repo.similarProfilesOverride = listOf(testProfile(name = "Allianz AG", organization = "Allianz AG"))

        val (_, confidence) = matcher.findBestMatch(null, "AG")

        assertThat(confidence).isEqualTo(0f)
    }

    @Test
    fun `the type filter is applied before scoring, so the runner-up of the right type is found`() = runTest {
        val person = testProfile(id = "person", name = "Jobcenter Berlin", organization = "Jobcenter Berlin", type = ProfileType.PERSON)
        val authority = testProfile(id = "authority", name = "Jobcenter", organization = "Jobcenter Berlin", type = ProfileType.AUTHORITY)
        repo.similarProfilesOverride = listOf(person, authority)

        val (profile, _) = matcher.findBestMatch(null, "Jobcenter Berlin", profileType = { it == ProfileType.AUTHORITY })

        assertThat(profile?.id).isEqualTo("authority")
    }

    @Test
    fun `a repository failure degrades to no match rather than throwing`() = runTest {
        repo.failWith = PamError.DatabaseError(IllegalStateException("boom"))

        val (profile, confidence) = matcher.findBestMatch(null, "Jobcenter")

        assertThat(profile).isNull()
        assertThat(confidence).isEqualTo(0f)
    }
}
