package com.postsaimanager.core.domain.contacts

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** What the user can do to a contact on the organisation page: edit, no longer responsible, merge, move, delete. */
class ContactEditsTest {

    private val contacts = FakeContactRepository()
    private val profiles = FakeProfileRepository()

    private fun contact(id: String, name: String, organisation: String = "jc", lastSeen: Long = 10) =
        ContactPerson(id, organisation, name, firstSeen = 1, lastSeen = lastSeen)

    private suspend fun stored(id: String) = (contacts.getContact(id) as PamResult.Success).data

    @Test
    fun `an edit is saved trimmed, a blank detail is cleared and the name cannot be empty`() = runTest {
        contacts.seed(contact("c1", "Frau Müller").copy(phone = "030 1", email = "m@jc.example"))
        val update = UpdateContactUseCase(contacts)

        val saved = update(stored("c1").copy(name = "  Frau Anna Müller ", title = " Teamleiterin ", phone = " ", department = "Team 5"))
        val refused = update(stored("c1").copy(name = "   "))

        assertThat(saved).isEqualTo(PamResult.Success(Unit))
        with(stored("c1")) {
            assertThat(name).isEqualTo("Frau Anna Müller")
            assertThat(title).isEqualTo("Teamleiterin")
            assertThat(phone).isNull()
            assertThat(email).isEqualTo("m@jc.example")
            assertThat(department).isEqualTo("Team 5")
        }
        assertThat(refused).isInstanceOf(PamResult.Error::class.java)
        assertThat(stored("c1").name).isEqualTo("Frau Anna Müller")
    }

    @Test
    fun `no longer responsible takes a contact out of current, and responsible again puts it back`() = runTest {
        contacts.seed(contact("old", "Nadine Beispiel", lastSeen = 10), contact("new", "Frau Müller", lastSeen = 20))
        val setActive = SetContactActiveUseCase(contacts)
        val observe = ObserveOrganisationContactsUseCase(contacts)
        assertThat(observe("jc").first().current?.name).isEqualTo("Frau Müller")

        setActive("new", false)

        assertThat(observe("jc").first().current?.name).isEqualTo("Nadine Beispiel")
        assertThat(observe("jc").first().earlier.single().active).isFalse()

        setActive("new", true)

        assertThat(observe("jc").first().current?.name).isEqualTo("Frau Müller")
    }

    @Test
    fun `merging folds a contact into another and its letters follow`() = runTest {
        contacts.seed(contact("nadine", "Nadine Beispiel"), contact("n", "N. Beispiel"))
        contacts.linkContactToDocument("n", "d3")

        MergeContactsUseCase(contacts)("nadine", "n")

        assertThat(contacts.observeContacts("jc").first().map { it.id }).containsExactly("nadine")
        assertThat(contacts.observeContactsForDocument("d3").first().map { it.id }).containsExactly("nadine")
    }

    @Test
    fun `moving a contact goes to another organisation profile only`() = runTest {
        profiles.seed(testProfile(id = "jc"), testProfile(id = "jc2", name = "Jobcenter Nordstadt"), testProfile(id = "p", kind = ProfileKind.PERSON))
        contacts.seed(contact("c1", "Frau Müller"))
        val move = MoveContactUseCase(contacts, profiles)

        val toPerson = move("c1", "p")
        val toNowhere = move("c1", "ghost")
        val same = move("c1", "jc")
        val moved = move("c1", "jc2")

        assertThat(toPerson).isInstanceOf(PamResult.Error::class.java)
        assertThat(toNowhere).isInstanceOf(PamResult.Error::class.java)
        assertThat(same).isEqualTo(PamResult.Success(Unit))
        assertThat(moved).isEqualTo(PamResult.Success(Unit))
        assertThat(stored("c1").organisationId).isEqualTo("jc2")
        assertThat(contacts.observeContacts("jc").first()).isEmpty()
    }

    @Test
    fun `deleting a contact keeps the letters, which only lose the link, and remembers the removal`() = runTest {
        contacts.seed(contact("c1", "Frau Müller"))
        contacts.linkContactToDocument("c1", "d1")

        val result = DeleteContactUseCase(contacts)("c1")

        assertThat(result).isEqualTo(PamResult.Success(Unit))
        assertThat(contacts.getContact("c1")).isInstanceOf(PamResult.Error::class.java)
        assertThat(contacts.observeContactsForDocument("d1").first()).isEmpty()
        assertThat(contacts.isRemovedFromDocument("d1", "Frau Müller")).isTrue()
    }
}
