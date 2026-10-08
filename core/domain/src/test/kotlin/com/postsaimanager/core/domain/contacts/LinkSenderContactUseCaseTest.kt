package com.postsaimanager.core.domain.contacts

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.contacts.DecideSameContactUseCase
import com.postsaimanager.core.domain.document.contacts.ReadContact
import com.postsaimanager.core.domain.document.contacts.SameContact
import com.postsaimanager.core.domain.document.contacts.SameContactProfile
import com.postsaimanager.core.domain.document.contacts.SameContactQuestion
import com.postsaimanager.core.domain.form.BaselineScores
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.ValueSource
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The linking flow with a fake same-person decision. The scenario is the one of the device pass: three letters from "Jobcenter
 * Musterstadt", signed by Frau Nadine Beispiel, then Frau Müller, then "N. Beispiel".
 */
class LinkSenderContactUseCaseTest {

    /** Scores a candidate [same] (Yes by a wide margin) when its name is in the set, else clearly No; can be made to fail. */
    private class FakeSameContact : SameContact {
        var sameAs: Set<String> = emptySet()
        var error: PamError? = null
        val questions = mutableListOf<SameContactQuestion>()

        override suspend fun score(question: SameContactQuestion): PamResult<BaselineScores> {
            questions += question
            error?.let { return PamResult.Error(it) }
            return PamResult.Success(BaselineScores(question.candidates.map { if (it.name in sameAs) 4.0 else -4.0 }, 0.0))
        }
    }

    private val documents = FakeDocumentRepository()
    private val profiles = FakeProfileRepository()
    private val contacts = FakeContactRepository()
    private val same = FakeSameContact()
    private val link = LinkSenderContactUseCase(documents, profiles, contacts, DecideSameContactUseCase(same, SameContactProfile()))

    private val jobcenter = testProfile(id = "jc", name = "Jobcenter Musterstadt", organization = "Jobcenter Musterstadt")

    init {
        documents.seed(
            testDocument(id = "d1", createdAt = 1_000),
            testDocument(id = "d2", createdAt = 2_000),
            testDocument(id = "d3", createdAt = 3_000),
        )
    }

    private fun contactField(documentId: String, name: String, confidence: Float = 0.9f, source: ValueSource = ValueSource.MACHINE) =
        ExtractedData(
            id = "c-$documentId", documentId = documentId, fieldName = UnderstandingToFields.CONTACT_PERSON, fieldValue = name,
            fieldType = ExtractedFieldType.PERSON_NAME, confidence = confidence, source = source, slotKey = UnderstandingToFields.SLOT_CONTACT,
        )

    private suspend fun senderIs(documentId: String) {
        if (profiles.getProfileById("jc") is PamResult.Error) profiles.seed(jobcenter)
        profiles.linkProfileToDocument("jc", documentId, ProfileRole.SENDER)
    }

    private suspend fun contactsOfJobcenter() = contacts.observeContacts("jc").first()

    @Test
    fun `the first letter creates a contact and links it, and no profile`() = runTest {
        documents.seedExtracted("d1", contactField("d1", "Frau Nadine Beispiel"))
        senderIs("d1")

        val outcome = link("d1")

        assertThat(outcome).isInstanceOf(ContactLinkOutcome.Created::class.java)
        val nadine = contactsOfJobcenter().single()
        assertThat(nadine.name).isEqualTo("Frau Nadine Beispiel")
        assertThat(nadine.firstSeen).isEqualTo(1_000)
        assertThat(nadine.lastSeen).isEqualTo(1_000)
        assertThat(contacts.observeContactsForDocument("d1").first().map { it.id }).containsExactly(nadine.id)
        assertThat(profiles.getProfiles().first().filter { it.kind == ProfileKind.PERSON }).isEmpty()
    }

    @Test
    fun `a MEDIUM reading links, a LOW one does not`() = runTest {
        documents.seedExtracted("d1", contactField("d1", "Frau Nadine Beispiel", confidence = 0.7f))
        senderIs("d1")
        documents.seedExtracted("d2", contactField("d2", "Frau Müller", confidence = 0.4f))
        senderIs("d2")

        assertThat(link("d1")).isInstanceOf(ContactLinkOutcome.Created::class.java)
        assertThat(link("d2")).isEqualTo(ContactLinkOutcome.NothingToLink)
        assertThat(contactsOfJobcenter().map { it.name }).containsExactly("Frau Nadine Beispiel")
    }

    @Test
    fun `confirming a contact confirms the letters' contact fields that name it`() = runTest {
        documents.seedExtracted("d1", contactField("d1", "Frau Nadine Beispiel", confidence = 0.7f))
        senderIs("d1")
        link("d1")
        val nadine = contactsOfJobcenter().single()

        val result = ConfirmContactUseCase(contacts, documents)(nadine.id)

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        val field = documents.observeExtractedData("d1").first().single()
        assertThat(field.reviewState).isEqualTo(ReviewState.CONFIRMED)
    }

    @Test
    fun `a different person becomes the second contact and the newest one is current`() = runTest {
        documents.seedExtracted("d1", contactField("d1", "Frau Nadine Beispiel"))
        senderIs("d1")
        link("d1")
        documents.seedExtracted("d2", contactField("d2", "Frau Müller"))
        senderIs("d2")

        val outcome = link("d2")

        // "Frau Müller" shares no name token with "Frau Nadine Beispiel" except "frau": the pre-filter lets the question through,
        // and the model's answer (No) decides.
        assertThat(outcome).isInstanceOf(ContactLinkOutcome.Created::class.java)
        assertThat(contactsOfJobcenter().map { it.name }).containsExactly("Frau Müller", "Frau Nadine Beispiel")
        val grouped = ObserveOrganisationContactsUseCase(contacts, documents).group(contactsOfJobcenter())
        assertThat(grouped.current?.name).isEqualTo("Frau Müller")
        assertThat(grouped.earlier.map { it.name }).containsExactly("Frau Nadine Beispiel")
    }

    @Test
    fun `N Beispiel on the third letter is matched to Nadine by the decision and updates her`() = runTest {
        documents.seedExtracted("d1", contactField("d1", "Frau Nadine Beispiel"))
        senderIs("d1")
        link("d1")
        documents.seedExtracted("d2", contactField("d2", "Frau Müller"))
        senderIs("d2")
        link("d2")
        documents.seedExtracted("d3", contactField("d3", "N. Beispiel"))
        senderIs("d3")
        same.sameAs = setOf("Frau Nadine Beispiel")

        val outcome = link("d3")

        assertThat(outcome).isInstanceOf(ContactLinkOutcome.Matched::class.java)
        val nadine = contactsOfJobcenter().single { it.name == "Frau Nadine Beispiel" }
        assertThat((outcome as ContactLinkOutcome.Matched).contactId).isEqualTo(nadine.id)
        assertThat(nadine.lastSeen).isEqualTo(3_000)
        assertThat(contactsOfJobcenter()).hasSize(2)
        assertThat(contacts.observeContactsForDocument("d3").first().map { it.id }).containsExactly(nadine.id)
        // The question was asked about the organisation as the user knows it, with the contacts as candidates ("Müller" shares no token).
        assertThat(same.questions.last().organisation).isEqualTo("Jobcenter Musterstadt")
        assertThat(same.questions.last().candidates.map { it.name }).containsExactly("Frau Nadine Beispiel")
    }

    @Test
    fun `the decision's scores are returned for the debug log`() = runTest {
        documents.seedExtracted("d1", contactField("d1", "Frau Nadine Beispiel"))
        senderIs("d1")
        link("d1")
        documents.seedExtracted("d3", contactField("d3", "N. Beispiel"))
        senderIs("d3")
        same.sameAs = setOf("Frau Nadine Beispiel")

        val outcome = link("d3") as ContactLinkOutcome.Matched

        assertThat(outcome.decision?.asked).hasSize(1)
        assertThat(outcome.decision?.asked?.single()?.score).isEqualTo(4.0)
        assertThat(outcome.decision?.baseline).isEqualTo(0.0)
    }

    @Test
    fun `a sender that is not resolved yet keeps the contact waiting, and it is attached once the sender is linked`() = runTest {
        documents.seedExtracted("d1", contactField("d1", "Frau Müller"))

        assertThat(link("d1")).isEqualTo(ContactLinkOutcome.Pending(PendingReason.SENDER_UNRESOLVED))
        assertThat(contacts.observeContactCounts().first()).isEmpty()

        senderIs("d1")

        assertThat(link("d1")).isInstanceOf(ContactLinkOutcome.Created::class.java)
        assertThat(contactsOfJobcenter().map { it.name }).containsExactly("Frau Müller")
    }

    @Test
    fun `a decision that cannot be answered leaves the contact waiting and links nothing`() = runTest {
        documents.seedExtracted("d1", contactField("d1", "Frau Nadine Beispiel"))
        senderIs("d1")
        link("d1")
        documents.seedExtracted("d3", contactField("d3", "N. Beispiel"))
        senderIs("d3")
        same.error = PamError.DatabaseError(IllegalStateException("no model"))

        val outcome = link("d3")

        assertThat(outcome).isEqualTo(ContactLinkOutcome.Pending(PendingReason.DECISION_FAILED))
        assertThat(contactsOfJobcenter().map { it.name }).containsExactly("Frau Nadine Beispiel")
        assertThat(contacts.observeContactsForDocument("d3").first()).isEmpty()

        same.error = null
        same.sameAs = setOf("Frau Nadine Beispiel")
        assertThat(link("d3")).isInstanceOf(ContactLinkOutcome.Matched::class.java)
    }

    @Test
    fun `a value the user edited is never overwritten, a missing one is filled`() = runTest {
        contacts.seed(
            ContactPerson(
                "nadine", "jc", "Frau Nadine Beispiel", title = "Teamleiterin (edited)", phone = "030 111 (edited)", email = null,
                firstSeen = 500, lastSeen = 500,
            ),
        )
        profiles.seed(jobcenter)
        documents.seedExtracted("d3", contactField("d3", "N. Beispiel"))
        senderIs("d3")
        same.sameAs = setOf("Frau Nadine Beispiel")

        val outcome = link.link("d3", ReadContact("N. Beispiel", title = "Sachbearbeiterin", phone = "030 999", email = "n.beispiel@jobcenter-musterstadt.example"))

        assertThat(outcome).isInstanceOf(ContactLinkOutcome.Matched::class.java)
        val nadine = contactsOfJobcenter().single()
        assertThat(nadine.title).isEqualTo("Teamleiterin (edited)")
        assertThat(nadine.phone).isEqualTo("030 111 (edited)")
        assertThat(nadine.email).isEqualTo("n.beispiel@jobcenter-musterstadt.example")
        assertThat(nadine.lastSeen).isEqualTo(3_000)
        assertThat(nadine.firstSeen).isEqualTo(500)
    }

    @Test
    fun `a letter that names a contact the user typed confirms its field, so the contact is never a suggestion`() = runTest {
        contacts.seed(ContactPerson("typed", "jc", "Frau Nadine Beispiel", firstSeen = 500, lastSeen = 500))
        documents.seedExtracted("d3", contactField("d3", "Frau Nadine Beispiel"))
        senderIs("d3")
        same.sameAs = setOf("Frau Nadine Beispiel")

        link("d3")

        assertThat(documents.observeExtractedData("d3").first().single().reviewState).isEqualTo(ReviewState.CONFIRMED)
    }

    @Test
    fun `a letter that names a contact an earlier letter made leaves its field for the person to answer`() = runTest {
        documents.seedExtracted("d1", contactField("d1", "Frau Nadine Beispiel"))
        senderIs("d1")
        link("d1")
        documents.seedExtracted("d3", contactField("d3", "N. Beispiel"))
        senderIs("d3")
        same.sameAs = setOf("Frau Nadine Beispiel")

        link("d3")

        assertThat(documents.observeExtractedData("d3").first().single().reviewState).isEqualTo(ReviewState.UNREVIEWED)
    }

    @Test
    fun `the contact name is found in the letter and its surroundings are the excerpt`() = runTest {
        documents.seedPages("d1", DocumentPage("p1", "d1", 1, "file:///1.jpg", ocrText = "Ihr Antrag.\nMit freundlichen Grüßen\nFrau Nadine Beispiel\nTel 030 111"))
        documents.seedExtracted("d1", contactField("d1", "Frau Nadine Beispiel"))
        senderIs("d1")
        link("d1")
        documents.seedPages("d3", DocumentPage("p3", "d3", 1, "file:///3.jpg", ocrText = "Bescheid.\nGrüße\nN. Beispiel\nZimmer 2.14"))
        documents.seedExtracted("d3", contactField("d3", "N. Beispiel"))
        senderIs("d3")

        link("d3")

        assertThat(same.questions.last().excerpt).contains("Zimmer 2.14")
    }

    @Test
    fun `nothing is linked for an ignored field, a low confidence field, a removed contact or a letter that has one`() = runTest {
        senderIs("d1")
        documents.seedExtracted("d1", contactField("d1", "Frau Müller").copy(reviewState = ReviewState.IGNORED))
        assertThat(link("d1")).isEqualTo(ContactLinkOutcome.NothingToLink)

        senderIs("d2")
        documents.seedExtracted("d2", contactField("d2", "Frau Müller", confidence = 0.3f))
        assertThat(link("d2")).isEqualTo(ContactLinkOutcome.NothingToLink)

        // A person's own value counts whatever the model's confidence was.
        documents.seedExtracted("d2", contactField("d2", "Frau Müller", confidence = 0.3f, source = ValueSource.USER))
        assertThat(link("d2")).isInstanceOf(ContactLinkOutcome.Created::class.java)
        assertThat(link("d2")).isEqualTo(ContactLinkOutcome.NothingToLink)

        senderIs("d3")
        documents.seedExtracted("d3", contactField("d3", "Herr Schulz"))
        contacts.removed += "d3" to "herr schulz"
        assertThat(link("d3")).isEqualTo(ContactLinkOutcome.NothingToLink)
        assertThat(contacts.observeContactsForDocument("d3").first()).isEmpty()
    }

    @Test
    fun `a letter without a contact field links nothing`() = runTest {
        senderIs("d1")

        assertThat(link("d1")).isEqualTo(ContactLinkOutcome.NothingToLink)
    }
}
