package com.postsaimanager.core.domain.contacts

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.letterContactsFor
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LoadLetterContactsUseCaseTest {

    private val profiles = FakeProfileRepository()
    private val contacts = FakeContactRepository()
    private val load = letterContactsFor(profiles, contacts)

    private val nadine = ContactPerson("nadine", "jc", "Frau Nadine Beispiel", phone = "030 111", firstSeen = 1, lastSeen = 10)
    private val mueller = ContactPerson("mueller", "jc", "Frau Müller", phone = "030 222", email = "mueller@jc.example", firstSeen = 20, lastSeen = 30)

    @Test
    fun `the letter's contact and the organisation's current contact are different people`() = runTest {
        profiles.seed(testProfile(id = "jc", name = "Jobcenter Musterstadt", organization = "Jobcenter Musterstadt"))
        profiles.linkProfileToDocument("jc", "d1", ProfileRole.SENDER)
        contacts.seed(nadine, mueller)
        contacts.linkContactToDocument("nadine", "d1")

        val found = load("d1")

        assertThat(found.letterContact?.name).isEqualTo("Frau Nadine Beispiel")
        assertThat(found.current?.name).isEqualTo("Frau Müller")
        assertThat(found.organisationName).isEqualTo("Jobcenter Musterstadt")
        assertThat(found.people.map { it.id }).containsExactly("nadine", "mueller")
    }

    @Test
    fun `a letter with no linked contact still has the sender organisation's current one`() = runTest {
        profiles.seed(testProfile(id = "jc", name = "Jobcenter Musterstadt"))
        profiles.linkProfileToDocument("jc", "d1", ProfileRole.SENDER)
        contacts.seed(nadine, mueller)

        val found = load("d1")

        assertThat(found.letterContact).isNull()
        assertThat(found.current?.id).isEqualTo("mueller")
        assertThat(found.people.map { it.id }).containsExactly("mueller")
    }

    @Test
    fun `a letter without a resolved sender has no contacts`() = runTest {
        contacts.seed(nadine)

        val found = load("d1")

        assertThat(found).isEqualTo(LetterContacts())
        assertThat(found.people).isEmpty()
    }
}
