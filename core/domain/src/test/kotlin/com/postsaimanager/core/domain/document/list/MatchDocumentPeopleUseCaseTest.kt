package com.postsaimanager.core.domain.document.list

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.DocumentProfileLink
import com.postsaimanager.core.model.PersonRole
import com.postsaimanager.core.model.PersonTag
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import org.junit.jupiter.api.Test

class MatchDocumentPeopleUseCaseTest {

    private val match = MatchDocumentPeopleUseCase()

    private fun profile(id: String, name: String, type: ProfileType = ProfileType.FAMILY_MEMBER) =
        Profile(id = id, type = type, name = name, createdAt = 0L, modifiedAt = 0L)

    private val me = profile("me", "Erika Mustermann", ProfileType.USER_SELF)
    private val maria = profile("maria", "Maria Mustermann")
    private val authority = profile("auth", "Stadtwerke Musterstadt", ProfileType.AUTHORITY)

    private fun people(
        addressee: String? = null,
        subject: String? = null,
        profiles: List<Profile> = listOf(me, maria, authority),
        links: List<DocumentProfileLink> = emptyList(),
    ) = match(DocumentParties(addressee, subject), profiles, links)

    @Test
    fun `an addressee that is the Me profile is for me`() {
        assertThat(people(addressee = "Frau Erika MUSTERMANN")).containsExactly(PersonTag("me", "Erika", PersonRole.FOR, isMe = true))
    }

    @Test
    fun `an addressee that is a managed profile is for that person`() {
        assertThat(people(addressee = "maria mustermann")).containsExactly(PersonTag("maria", "Maria", PersonRole.FOR, isMe = false))
    }

    @Test
    fun `a subject person other than the addressee is about that person`() {
        val result = people(addressee = "Erika Mustermann", subject = "Maria Mustermann")
        assertThat(result).containsExactly(
            PersonTag("me", "Erika", PersonRole.FOR, isMe = true),
            PersonTag("maria", "Maria", PersonRole.ABOUT, isMe = false),
        ).inOrder()
    }

    @Test
    fun `a person who is both the addressee and the subject is only for`() {
        assertThat(people(addressee = "Maria Mustermann", subject = "Maria Mustermann"))
            .containsExactly(PersonTag("maria", "Maria", PersonRole.FOR, isMe = false))
    }

    @Test
    fun `a stored link wins over the printed name`() {
        val result = people(
            addressee = "Erika Mustermann",
            links = listOf(DocumentProfileLink("d1", "maria", ProfileRole.RECEIVER)),
        )
        assertThat(result).containsExactly(PersonTag("maria", "Maria", PersonRole.FOR, isMe = false))
    }

    @Test
    fun `a stored mention link is about, and an authority is never a person chip`() {
        val result = people(
            links = listOf(DocumentProfileLink("d1", "maria", ProfileRole.RELATED), DocumentProfileLink("d1", "auth", ProfileRole.RECEIVER)),
        )
        assertThat(result).containsExactly(PersonTag("maria", "Maria", PersonRole.ABOUT, isMe = false))
    }

    @Test
    fun `no match and no parties give no people`() {
        assertThat(people(addressee = "Familie Beispiel")).isEmpty()
        assertThat(people()).isEmpty()
        assertThat(people(addressee = "Erika Mustermann", profiles = emptyList())).isEmpty()
    }

    @Test
    fun `a household line that does not spell the full name does not match`() {
        assertThat(people(addressee = "Max Mustermann und Erika")).isEmpty()
        assertThat(people(addressee = "Erika und Max Mustermann")).isEmpty()
    }

    @Test
    fun `a household line that spells the full name contiguously matches`() {
        assertThat(people(addressee = "Max und Erika Mustermann")).containsExactly(PersonTag("me", "Erika", PersonRole.FOR, isMe = true))
        assertThat(people(addressee = "Max Mustermann, Maria Mustermann")).containsExactly(PersonTag("maria", "Maria", PersonRole.FOR, isMe = false))
    }

    @Test
    fun `a first name alone never matches inside a longer line`() {
        val solo = profile("solo", "Maria")
        assertThat(people(addressee = "Maria Schmidt", profiles = listOf(solo))).isEmpty()
        assertThat(people(addressee = "maria", profiles = listOf(solo))).containsExactly(PersonTag("solo", "Maria", PersonRole.FOR, isMe = false))
    }

    @Test
    fun `two people with the same first name are told apart by the full name`() {
        val other = profile("m2", "Maria Berger")
        val result = people(addressee = "Maria Berger", subject = "Maria Mustermann", profiles = listOf(maria, other))
        assertThat(result.map { it.displayName }).containsExactly("Maria Berger", "Maria Mustermann").inOrder()
    }

    @Test
    fun `an arabic name matches by the same structure`() {
        val arabic = profile("a", "محمد علي")
        assertThat(people(addressee = "السيد محمد علي", profiles = listOf(arabic))).hasSize(1)
    }
}
