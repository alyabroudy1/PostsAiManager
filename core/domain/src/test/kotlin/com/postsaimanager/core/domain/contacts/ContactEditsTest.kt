package com.postsaimanager.core.domain.contacts

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.CustomDetail
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What the user can do to a contact on the organisation page: add, edit, no longer responsible, merge, move, delete, and what a
 * suggested contact (one a reading found) does when it is confirmed, edited or discarded, here or on the letter.
 */
class ContactEditsTest {

    private val contacts = FakeContactRepository()
    private val profiles = FakeProfileRepository()
    private val documents = FakeDocumentRepository()

    private fun contact(id: String, name: String, organisation: String = "jc", lastSeen: Long = 10) =
        ContactPerson(id, organisation, name, firstSeen = 1, lastSeen = lastSeen)

    private suspend fun stored(id: String) = (contacts.getContact(id) as PamResult.Success).data

    private fun contactField(documentId: String, name: String, source: com.postsaimanager.core.model.ValueSource = com.postsaimanager.core.model.ValueSource.MACHINE) =
        ExtractedData(
            id = "c-$documentId", documentId = documentId, fieldName = UnderstandingToFields.CONTACT_PERSON, fieldValue = name,
            fieldType = ExtractedFieldType.PERSON_NAME, confidence = 0.9f, source = source, slotKey = UnderstandingToFields.SLOT_CONTACT,
        )

    private suspend fun field(documentId: String) = documents.observeExtractedData(documentId).first().single()

    /** A contact a reading suggested: linked to d1, whose "Contact Person" field names it, nobody has answered. */
    private fun suggested(): String {
        documents.seed(testDocument(id = "d1", title = "Letter of 12 May"))
        documents.seedExtracted("d1", contactField("d1", "Frau Müller"))
        contacts.seed(contact("c1", "Frau Müller"))
        contacts.links += "c1" to "d1"
        return "c1"
    }

    @Test
    fun `an edit is saved trimmed, a blank detail is cleared and the name cannot be empty`() = runTest {
        contacts.seed(contact("c1", "Frau Müller").copy(phone = "030 1", email = "m@jc.example"))
        val update = UpdateContactUseCase(contacts, documents)

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
    fun `own details are kept in order, trimmed, and a half-empty one is dropped`() = runTest {
        contacts.seed(contact("c1", "Frau Müller"))

        UpdateContactUseCase(contacts, documents)(
            stored("c1").copy(
                customDetails = listOf(
                    CustomDetail(" Direct line ", " 030 99 "), CustomDetail("Room", ""), CustomDetail("", "x"), CustomDetail("Office hours", "Mo-Fr 8-12"),
                ),
            ),
        )

        assertThat(stored("c1").customDetails).containsExactly(CustomDetail("Direct line", "030 99"), CustomDetail("Office hours", "Mo-Fr 8-12")).inOrder()
    }

    // ---- Add contact (typed by the user) ----

    @Test
    fun `a contact typed on the organisation page is the user's own and never to check`() = runTest {
        profiles.seed(testProfile(id = "jc", kind = ProfileKind.ORGANISATION))

        val added = AddContactUseCase(contacts, profiles)(
            "jc", name = "  Herr Beispiel ", title = "Teamleiter", department = " ", phone = "030 5", email = null,
            customDetails = listOf(CustomDetail("Direct line", "030 6"), CustomDetail("", "")), now = 777,
        ) as PamResult.Success

        with(stored(added.data.id)) {
            assertThat(name).isEqualTo("Herr Beispiel")
            assertThat(title).isEqualTo("Teamleiter")
            assertThat(department).isNull()
            assertThat(phone).isEqualTo("030 5")
            assertThat(customDetails).containsExactly(CustomDetail("Direct line", "030 6"))
            assertThat(firstSeen).isEqualTo(777)
            assertThat(lastSeen).isEqualTo(777)
            assertThat(active).isTrue()
        }
        // No letter suggested it: it is linked to no letter, so no field can make it a suggestion.
        assertThat(contacts.documentIdsOf(added.data.id)).isEmpty()
        assertThat(contacts.toCheck.value).isEmpty()
        assertThat(contacts.observeContacts("jc").first().map { it.name }).containsExactly("Herr Beispiel")
    }

    @Test
    fun `a contact needs a name and an organisation`() = runTest {
        profiles.seed(testProfile(id = "jc", kind = ProfileKind.ORGANISATION), testProfile(id = "p", kind = ProfileKind.PERSON))
        val add = AddContactUseCase(contacts, profiles)

        assertThat(add("jc", name = "  ")).isInstanceOf(PamResult.Error::class.java)
        assertThat(add("p", name = "Herr Beispiel")).isInstanceOf(PamResult.Error::class.java)
        assertThat(add("ghost", name = "Herr Beispiel")).isInstanceOf(PamResult.Error::class.java)
        assertThat(contacts.observeContactCounts().first()).isEmpty()
    }

    // ---- A suggested contact: confirm, edit, discard (organisation page) ----

    @Test
    fun `confirming a suggested contact confirms the letter's field`() = runTest {
        val id = suggested()

        ConfirmContactUseCase(contacts, documents)(id)

        assertThat(field("d1").reviewState).isEqualTo(ReviewState.CONFIRMED)
        assertThat(stored(id).name).isEqualTo("Frau Müller")
    }

    @Test
    fun `editing a suggested contact renames the letter's field, which is then edited and so answered`() = runTest {
        val id = suggested()

        UpdateContactUseCase(contacts, documents)(stored(id).copy(name = "Frau Anna Müller"))

        assertThat(stored(id).name).isEqualTo("Frau Anna Müller")
        with(field("d1")) {
            assertThat(fieldValue).isEqualTo("Frau Anna Müller")
            assertThat(reviewState).isEqualTo(ReviewState.EDITED)
        }
    }

    @Test
    fun `editing a suggested contact without changing its name confirms the field`() = runTest {
        val id = suggested()

        UpdateContactUseCase(contacts, documents)(stored(id).copy(title = "Teamleiterin"))

        assertThat(field("d1").reviewState).isEqualTo(ReviewState.CONFIRMED)
        assertThat(stored(id).title).isEqualTo("Teamleiterin")
    }

    @Test
    fun `discarding a suggested contact ignores the letter's field, removes the contact and remembers the removal`() = runTest {
        val id = suggested()

        val result = DiscardContactUseCase(contacts, documents)(id)

        assertThat(result).isEqualTo(PamResult.Success(Unit))
        assertThat(field("d1").reviewState).isEqualTo(ReviewState.IGNORED)
        assertThat(contacts.getContact(id)).isInstanceOf(PamResult.Error::class.java)
        // The tombstone: reading the letter again links no contact.
        assertThat(contacts.isRemovedFromDocument("d1", "Frau Müller")).isTrue()
    }

    // ---- The same contact in the Extracted tab ----

    @Test
    fun `editing the contact field on the letter renames the contact`() = runTest {
        val id = suggested()

        LetterContactFields(contacts, documents, DiscardContactUseCase(contacts, documents)).edit("d1", "c-d1", "Contact Person", "Frau Anna Müller")

        assertThat(stored(id).name).isEqualTo("Frau Anna Müller")
        assertThat(field("d1").reviewState).isEqualTo(ReviewState.EDITED)
    }

    @Test
    fun `ignoring the contact field on the letter discards the contact with its tombstone`() = runTest {
        val id = suggested()

        LetterContactFields(contacts, documents, DiscardContactUseCase(contacts, documents)).ignore("d1", listOf("c-d1"))

        assertThat(field("d1").reviewState).isEqualTo(ReviewState.IGNORED)
        assertThat(contacts.getContact(id)).isInstanceOf(PamResult.Error::class.java)
        assertThat(contacts.isRemovedFromDocument("d1", "Frau Müller")).isTrue()
    }

    @Test
    fun `any other field of the letter is only edited or ignored`() = runTest {
        documents.seed(testDocument(id = "d1"))
        documents.seedExtracted(
            "d1",
            ExtractedData(
                id = "f1", documentId = "d1", fieldName = "Reference", fieldValue = "AZ 1", fieldType = ExtractedFieldType.REFERENCE_NUMBER,
                confidence = 0.9f, slotKey = "reference",
            ),
        )
        contacts.seed(contact("c1", "AZ 1"))
        contacts.links += "c1" to "d1"
        val fields = LetterContactFields(contacts, documents, DiscardContactUseCase(contacts, documents))

        fields.edit("d1", "f1", "Reference", "AZ 2")
        assertThat(field("d1").fieldValue).isEqualTo("AZ 2")
        fields.ignore("d1", listOf("f1"))

        assertThat(field("d1").reviewState).isEqualTo(ReviewState.IGNORED)
        assertThat(stored("c1").name).isEqualTo("AZ 1")
    }

    // ---- The other edits ----

    @Test
    fun `no longer responsible takes a contact out of current, and responsible again puts it back`() = runTest {
        contacts.seed(contact("old", "Nadine Beispiel", lastSeen = 10), contact("new", "Frau Müller", lastSeen = 20))
        val setActive = SetContactActiveUseCase(contacts)
        val observe = ObserveOrganisationContactsUseCase(contacts, documents)
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
