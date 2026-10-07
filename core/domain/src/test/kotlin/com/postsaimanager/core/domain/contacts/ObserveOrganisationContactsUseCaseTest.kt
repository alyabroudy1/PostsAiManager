package com.postsaimanager.core.domain.contacts

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.testing.FakeContactRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ObserveOrganisationContactsUseCaseTest {

    private val repository = FakeContactRepository()
    private val observe = ObserveOrganisationContactsUseCase(repository)

    private fun contact(id: String, lastSeen: Long, active: Boolean = true, organisation: String = "jc") =
        ContactPerson(id, organisation, id, firstSeen = 1, lastSeen = lastSeen, active = active)

    @Test
    fun `the contact of the newest letter is current, the others are earlier, newest first`() = runTest {
        repository.seed(contact("old", 10), contact("newest", 30), contact("middle", 20), contact("elsewhere", 99, organisation = "other"))

        observe("jc").test {
            val found = awaitItem()
            assertThat(found.current?.id).isEqualTo("newest")
            assertThat(found.earlier.map { it.id }).containsExactly("middle", "old").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a contact marked no longer responsible is never current`() = runTest {
        repository.seed(contact("gone", 30, active = false), contact("here", 20))

        observe("jc").test {
            val found = awaitItem()
            assertThat(found.current?.id).isEqualTo("here")
            assertThat(found.earlier.map { it.id }).containsExactly("gone")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an organisation without contacts is empty`() = runTest {
        observe("jc").test {
            val found = awaitItem()
            assertThat(found.isEmpty).isTrue()
            assertThat(found.current).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
