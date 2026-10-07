package com.postsaimanager.core.data.repository

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.data.database.entity.ProfileEntity
import com.postsaimanager.core.model.ContactPerson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The contact data path on the real schema (in-memory Room, foreign keys on). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactRepositoryImplTest {

    private lateinit var db: PamDatabase
    private lateinit var contacts: ContactRepositoryImpl

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication() as Context, PamDatabase::class.java)
            .allowMainThreadQueries().build()
        contacts = ContactRepositoryImpl(db, db.contactDao(), db.dismissedEntityDao(), Dispatchers.Unconfined)
        runBlocking {
            listOf("jc", "tax").forEach { db.profileDao().insert(organisation(it)) }
            listOf("d1", "d2", "d3").forEach { db.documentDao().insert(document(it)) }
        }
    }

    @After
    fun close() = db.close()

    private fun organisation(id: String) = ProfileEntity(
        id = id, type = "AUTHORITY", name = id, organization = null, department = null, street = null, city = null,
        postalCode = null, country = null, phone = null, email = null, website = null, reference = null, notes = null,
        completionScore = 0f, missingFields = null, avatarPath = null, createdAt = 1, modifiedAt = 1, kind = "ORGANISATION",
    )

    private fun document(id: String) = DocumentEntity(
        id = id, title = id, status = "COMPLETED", documentType = null, language = null, sourceType = "SCAN",
        thumbnailPath = null, pageCount = 1, createdAt = 1, modifiedAt = 1,
    )

    private fun contact(id: String, organisation: String = "jc", lastSeen: Long = 10, firstSeen: Long = 1) =
        ContactPerson(id = id, organisationId = organisation, name = "Name $id", firstSeen = firstSeen, lastSeen = lastSeen)

    @Test
    fun `contacts of an organisation come newest first and counts are per organisation`(): Unit = runBlocking {
        contacts.addContact(contact("old", lastSeen = 5))
        contacts.addContact(contact("new", lastSeen = 50))
        contacts.addContact(contact("other", organisation = "tax"))

        assertThat(contacts.observeContacts("jc").first().map { it.id }).containsExactly("new", "old").inOrder()
        assertThat(contacts.observeContactCounts().first()).containsExactly("jc", 2, "tax", 1)
    }

    @Test
    fun `a contact round-trips, can be updated and marked inactive`(): Unit = runBlocking {
        contacts.addContact(contact("c1").copy(title = "Sachbearbeiterin", phone = "030 1", email = "a@b.de", room = "2.14", department = "Team 5"))

        contacts.updateContact((contacts.getContact("c1") as PamResult.Success).data.copy(phone = "030 2"))
        contacts.setActive("c1", false)

        val loaded = (contacts.getContact("c1") as PamResult.Success).data
        assertThat(loaded.phone).isEqualTo("030 2")
        assertThat(loaded.title).isEqualTo("Sachbearbeiterin")
        assertThat(loaded.room).isEqualTo("2.14")
        assertThat(loaded.active).isFalse()
        assertThat(contacts.getContact("ghost")).isInstanceOf(PamResult.Error::class.java)
    }

    @Test
    fun `the contacts of a document are the ones linked to it`(): Unit = runBlocking {
        contacts.addContact(contact("c1"))
        contacts.addContact(contact("c2"))
        contacts.linkContactToDocument("c1", "d1")
        contacts.linkContactToDocument("c1", "d1")
        contacts.linkContactToDocument("c2", "d2")

        assertThat(contacts.observeContactsForDocument("d1").first().map { it.id }).containsExactly("c1")

        contacts.unlinkContactFromDocument("c1", "d1")

        assertThat(contacts.observeContactsForDocument("d1").first()).isEmpty()
        assertThat(contacts.observeContactsForDocument("d2").first().map { it.id }).containsExactly("c2")
    }

    @Test
    fun `merging keeps one contact with both seen-ranges, the blanks filled and all documents`(): Unit = runBlocking {
        contacts.addContact(contact("keep", firstSeen = 10, lastSeen = 20).copy(phone = "030 1"))
        contacts.addContact(contact("merged", firstSeen = 5, lastSeen = 30).copy(phone = "030 9", email = "n@jc.de"))
        contacts.linkContactToDocument("keep", "d1")
        contacts.linkContactToDocument("merged", "d1")
        contacts.linkContactToDocument("merged", "d2")

        val result = contacts.mergeContacts("keep", "merged")

        assertThat(result).isEqualTo(PamResult.Success(Unit))
        val kept = (contacts.getContact("keep") as PamResult.Success).data
        assertThat(kept.phone).isEqualTo("030 1")
        assertThat(kept.email).isEqualTo("n@jc.de")
        assertThat(kept.firstSeen).isEqualTo(5)
        assertThat(kept.lastSeen).isEqualTo(30)
        assertThat(contacts.getContact("merged")).isInstanceOf(PamResult.Error::class.java)
        assertThat(contacts.observeContactsForDocument("d1").first().map { it.id }).containsExactly("keep")
        assertThat(contacts.observeContactsForDocument("d2").first().map { it.id }).containsExactly("keep")
    }

    @Test
    fun `contacts of two organisations cannot be merged`(): Unit = runBlocking {
        contacts.addContact(contact("a"))
        contacts.addContact(contact("b", organisation = "tax"))

        assertThat(contacts.mergeContacts("a", "b")).isInstanceOf(PamResult.Error::class.java)
        assertThat(contacts.mergeContacts("a", "a")).isInstanceOf(PamResult.Error::class.java)
        assertThat(contacts.observeContactCounts().first()).containsExactly("jc", 1, "tax", 1)
    }

    @Test
    fun `a deleted contact is remembered for the letters it was linked to, and only for those`(): Unit = runBlocking {
        contacts.addContact(contact("c1").copy(name = "Frau Müller"))
        contacts.linkContactToDocument("c1", "d1")

        contacts.deleteContact("c1")

        assertThat(contacts.isRemovedFromDocument("d1", "  frau MÜLLER ")).isTrue()
        assertThat(contacts.isRemovedFromDocument("d2", "Frau Müller")).isFalse()
        assertThat(contacts.isRemovedFromDocument("d1", "Nadine Beispiel")).isFalse()
    }

    @Test
    fun `deleting a contact or its organisation removes the contact and its links`(): Unit = runBlocking {
        contacts.addContact(contact("c1"))
        contacts.addContact(contact("c2", organisation = "tax"))
        contacts.linkContactToDocument("c1", "d1")
        contacts.linkContactToDocument("c2", "d3")

        contacts.deleteContact("c1")
        db.profileDao().deleteById("tax")

        assertThat(contacts.observeContactCounts().first()).isEmpty()
        assertThat(contacts.observeContactsForDocument("d1").first()).isEmpty()
        assertThat(contacts.observeContactsForDocument("d3").first()).isEmpty()
    }
}
