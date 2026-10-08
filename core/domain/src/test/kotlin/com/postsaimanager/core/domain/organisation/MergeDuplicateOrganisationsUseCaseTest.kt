package com.postsaimanager.core.domain.organisation

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MergeDuplicateOrganisationsUseCaseTest {

    private val profiles = FakeProfileRepository()
    private val merge = MergeDuplicateOrganisationsUseCase(profiles)

    private fun machineOrg(id: String, createdAt: Long, modifiedAt: Long = createdAt, source: String? = "d-$id") =
        testProfile(id = id, name = "Jobcenter Musterstadt", organization = "Jobcenter Musterstadt")
            .copy(sourceDocumentId = source, sourceEntityName = "jobcenter musterstadt", createdAt = createdAt, modifiedAt = modifiedAt)

    private suspend fun ids() = profiles.getProfiles().first().map(Profile::id)

    @Test
    fun `two machine-made organisations with the same name become the older one, with the links`() = runTest {
        profiles.seed(machineOrg("new", 9), machineOrg("old", 5))
        profiles.linkProfileToDocument("new", "d-new", ProfileRole.SENDER)

        val merged = merge("jobcenter musterstadt")

        assertThat(merged).isEqualTo(1)
        assertThat(ids()).containsExactly("old")
        assertThat(profiles.links).containsExactly(Triple("old", "d-new", ProfileRole.SENDER))
    }

    @Test
    fun `an organisation the user edited or made by hand is not merged`() = runTest {
        profiles.seed(machineOrg("old", 5), machineOrg("edited", 9, modifiedAt = 12), machineOrg("byHand", 10, source = null))

        assertThat(merge("jobcenter musterstadt")).isEqualTo(0)
        assertThat(ids()).containsExactly("old", "edited", "byHand")
    }

    @Test
    fun `two that disagree on a detail are kept apart, and a missing detail is filled from the merged one`() = runTest {
        profiles.seed(
            machineOrg("a", 5).copy(street = "Musterstrasse 1"),
            machineOrg("b", 6).copy(street = "Andere Strasse 9"),
            machineOrg("c", 7).copy(phone = "0123 456"),
        )

        assertThat(merge("jobcenter musterstadt")).isEqualTo(1)

        assertThat(ids()).containsExactly("a", "b")
        assertThat(profiles.getProfiles().first().single { it.id == "a" }.phone).isEqualTo("0123 456")
    }

    @Test
    fun `another name is left alone`() = runTest {
        profiles.seed(machineOrg("a", 5), machineOrg("b", 6))

        assertThat(merge("amt beispiel")).isEqualTo(0)
        assertThat(ids()).containsExactly("a", "b")
    }
}
