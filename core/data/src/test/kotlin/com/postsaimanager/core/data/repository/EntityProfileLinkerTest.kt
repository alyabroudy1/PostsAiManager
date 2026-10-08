package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.database.dao.DismissedEntityDao
import com.postsaimanager.core.data.database.entity.DismissedEntityEntity
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.contacts.ContactLinkOutcome
import com.postsaimanager.core.domain.contacts.LinkSenderContactUseCase
import com.postsaimanager.core.domain.contacts.PendingReason
import com.postsaimanager.core.domain.document.contacts.DecideSameContactUseCase
import com.postsaimanager.core.domain.document.contacts.SameContact
import com.postsaimanager.core.domain.document.contacts.SameContactProfile
import com.postsaimanager.core.domain.document.contacts.SameContactQuestion
import com.postsaimanager.core.domain.form.BaselineScores
import com.postsaimanager.core.domain.organisation.DecideDetailOwnerUseCase
import com.postsaimanager.core.domain.organisation.DetailOwnerProfile
import com.postsaimanager.core.domain.organisation.SuggestOrganisationDetailsUseCase
import com.postsaimanager.core.domain.usecase.EntityLinkingUseCase
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.RecognisedEntity
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeDetailOwnerQuestion
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.FakeProfileSuggestionRepository
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [EntityProfileLinker] — the wiring that gives [EntityLinkingUseCase]'s decisions
 * a database to act on.
 *
 * The scenario throughout: a letter from Jobcenter Berlin Mitte, signed by Frau Müller,
 * addressed to Sam, mentioning his wife Layla. Organisations are linked and created automatically; the caseworker
 * is never a profile but a contact of that organisation (the same-person question is answered by a scripted fake here);
 * a person the app is unsure about is never created and never asked about.
 */
class EntityProfileLinkerTest {

    private val profileRepository = FakeProfileRepository()
    private val documents = FakeDocumentRepository()
    private val contacts = FakeContactRepository()
    private val dismissedDao = FakeDismissedEntityDao()
    private val matcher = ProfileMatcher(profileRepository)

    /** Says "same person" for a candidate whose name is in [sameAs], the made-up baseline scoring 0. */
    private class ScriptedSameContact : SameContact {
        var sameAs: Set<String> = emptySet()
        override suspend fun score(question: SameContactQuestion): PamResult<BaselineScores> =
            PamResult.Success(BaselineScores(question.candidates.map { if (it.name in sameAs) 5.0 else -5.0 }, 0.0))
    }

    private val sameContact = ScriptedSameContact()
    private val linker = EntityProfileLinker(
        matcher, profileRepository, dismissedDao, EntityLinkingUseCase(),
        dagger.Lazy { LinkSenderContactUseCase(documents, profileRepository, contacts, DecideSameContactUseCase(sameContact, SameContactProfile())) },
        dagger.Lazy {
            SuggestOrganisationDetailsUseCase(
                documents, profileRepository, contacts, FakeProfileSuggestionRepository(),
                DecideDetailOwnerUseCase(FakeDetailOwnerQuestion(), DetailOwnerProfile()),
            )
        },
    )

    init {
        listOf("doc-1", "doc-2", "doc-3").forEachIndexed { i, id -> documents.seed(testDocument(id = id, createdAt = (i + 1) * 1_000L)) }
    }

    /** The reading's stored "Contact Person" field, as the pipeline writes it before the linker runs. */
    private fun storeContact(documentId: String, name: String) = documents.seedExtracted(
        documentId,
        ExtractedData(
            id = "contact-$documentId", documentId = documentId, fieldName = UnderstandingToFields.CONTACT_PERSON, fieldValue = name,
            fieldType = ExtractedFieldType.PERSON_NAME, confidence = 0.9f, slotKey = UnderstandingToFields.SLOT_CONTACT,
        ),
    )

    private fun entity(
        name: String,
        kind: EntityKind,
        role: EntityRole,
        confidence: Float = 0.9f,
        relation: String = "",
    ) = RecognisedEntity(name = name, kind = kind, role = role, relation = relation, confidence = confidence)

    private suspend fun profiles() = profileRepository.getProfiles().first()

    private fun jobcenterLetter(vararg extra: RecognisedEntity) = DocumentUnderstanding(
        entities = listOf(
            entity("Jobcenter Berlin Mitte", EntityKind.AUTHORITY, EntityRole.SENDER),
            entity("Sam", EntityKind.PERSON, EntityRole.RECIPIENT),
            entity("Frau Müller", EntityKind.PERSON, EntityRole.SENDER_CONTACT),
            *extra,
        ),
    )

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("First scan of a new sender")
    inner class FirstScan {

        @Test
        fun `an unknown sender organisation is created and linked as SENDER`() = runTest {
            val outcome = linker.process("doc-1", jobcenterLetter())

            // Only the Jobcenter: the caseworker used to count as a second created profile, she is a contact now.
            assertThat(outcome.created).isEqualTo(1)
            val jobcenter = profiles()
                .single { it.organization == "Jobcenter Berlin Mitte" && it.kind == ProfileKind.ORGANISATION }
            assertThat(profileRepository.links).contains(
                Triple(jobcenter.id, "doc-1", ProfileRole.SENDER),
            )
        }

        @Test
        @DisplayName("the caseworker is a contact of that organisation, never a profile")
        fun `the sender contact becomes a contact of the sender organisation and no profile`() = runTest {
            storeContact("doc-1", "Frau Müller")

            val outcome = linker.process("doc-1", jobcenterLetter())

            // Replaces "the sender contact is created as a PERSON profile with the organisation attached, linked as CASE_WORKER".
            assertThat(profiles().none { it.name == "Frau Müller" }).isTrue()
            assertThat(profileRepository.links.none { it.third == ProfileRole.CASE_WORKER }).isTrue()
            val jobcenter = profiles().single { it.kind == ProfileKind.ORGANISATION }
            val contact = contacts.observeContacts(jobcenter.id).first().single()
            assertThat(contact.name).isEqualTo("Frau Müller")
            assertThat(contacts.observeContactsForDocument("doc-1").first().map { it.id }).containsExactly(contact.id)
            assertThat(outcome.contact).isInstanceOf(ContactLinkOutcome.Created::class.java)
        }

        @Test
        @DisplayName("Sam is never created and nothing is asked about him")
        fun `the recipient is not created`() = runTest {
            linker.process("doc-1", jobcenterLetter())

            assertThat(profiles().none { it.name == "Sam" }).isTrue()
            assertThat(profileRepository.links.none { it.third == ProfileRole.RECEIVER }).isTrue()
        }

        @Test
        @DisplayName("Layla is never created, even read with high confidence")
        fun `a mentioned family member is not created`() = runTest {
            val layla = entity("Layla", EntityKind.PERSON, EntityRole.MENTIONED, confidence = 0.95f)

            linker.process("doc-1", jobcenterLetter(layla))

            assertThat(profiles().none { it.name == "Layla" }).isTrue()
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Second letter from the same sender")
    inner class SecondLetter {

        @Test
        fun `links to the existing profile instead of creating a duplicate`() = runTest {
            linker.process("doc-1", jobcenterLetter())
            val firstRunProfiles = profiles().size

            val outcome = linker.process(
                "doc-2",
                DocumentUnderstanding(
                    entities = listOf(
                        entity("Jobcenter Berlin Mitte", EntityKind.AUTHORITY, EntityRole.SENDER),
                    ),
                ),
            )

            assertThat(outcome.created).isEqualTo(0)
            assertThat(outcome.linked).isEqualTo(1)
            // Still exactly the profiles the first letter made — no second Jobcenter row.
            assertThat(profiles()).hasSize(firstRunProfiles)
            val jobcenter = profiles()
                .single { it.organization == "Jobcenter Berlin Mitte" && it.kind == ProfileKind.ORGANISATION }
            assertThat(profileRepository.links).contains(
                Triple(jobcenter.id, "doc-2", ProfileRole.SENDER),
            )
        }

        @Test
        @DisplayName("the same caseworker on a second letter is the same contact, seen again")
        fun `an existing contact at the same organisation is matched, not recreated`() = runTest {
            storeContact("doc-1", "Frau Müller")
            linker.process("doc-1", jobcenterLetter())
            storeContact("doc-2", "Frau Müller")
            sameContact.sameAs = setOf("Frau Müller")

            val outcome = linker.process(
                "doc-2",
                DocumentUnderstanding(
                    entities = listOf(
                        entity("Jobcenter Berlin Mitte", EntityKind.AUTHORITY, EntityRole.SENDER),
                        entity("Frau Müller", EntityKind.PERSON, EntityRole.SENDER_CONTACT),
                    ),
                ),
            )

            // Replaces "profiles().count { it.name == "Frau Müller" } == 1": there is one contact, linked to both letters.
            assertThat(outcome.created).isEqualTo(0)
            assertThat(profiles().count { it.name == "Frau Müller" }).isEqualTo(0)
            val jobcenter = profiles().single { it.kind == ProfileKind.ORGANISATION }
            val contact = contacts.observeContacts(jobcenter.id).first().single()
            assertThat(contact.lastSeen).isEqualTo(2_000L)
            assertThat(contacts.observeContactsForDocument("doc-2").first().map { it.id }).containsExactly(contact.id)
            assertThat(outcome.contact).isInstanceOf(ContactLinkOutcome.Matched::class.java)
        }

        @Test
        @DisplayName("a weak match is left alone: no second Jobcenter row and no link to a guess")
        fun `a weak organisation match neither links nor creates`() = runTest {
            linker.process("doc-1", jobcenterLetter())
            val before = profiles().size
            val linksBefore = profileRepository.links.size

            val outcome = linker.process(
                "doc-2",
                DocumentUnderstanding(
                    entities = listOf(entity("Jobcenter Berlin", EntityKind.AUTHORITY, EntityRole.SENDER)),
                ),
            )

            assertThat(outcome.created).isEqualTo(0)
            assertThat(outcome.linked).isEqualTo(0)
            assertThat(profiles()).hasSize(before)
            assertThat(profileRepository.links).hasSize(linksBefore)
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("RECIPIENT linking to an existing USER_SELF")
    inner class RecipientLinking {

        @Test
        fun `links to the existing USER_SELF profile`() = runTest {
            val me = testProfile(id = "me", name = "Sam", type = ProfileType.USER_SELF)
            profileRepository.seed(me)

            linker.process("doc-1", jobcenterLetter())

            assertThat(profileRepository.links).contains(Triple("me", "doc-1", ProfileRole.RECEIVER))
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Dismissal sticks across reprocessing")
    inner class DismissalSticks {

        @Test
        @DisplayName("a dismissed contact is not recreated when the document is reprocessed")
        fun `a dismissed entity is ignored on the next run`() = runTest {
            storeContact("doc-1", "Frau Müller")
            linker.dismiss("doc-1", "Frau Müller")

            val outcome = linker.process("doc-1", jobcenterLetter())

            assertThat(profiles().none { it.name == "Frau Müller" }).isTrue()
            assertThat(outcome.ignoredAsDismissed).isEqualTo(1)
            // Neither a profile nor a contact: the contact linking is skipped for a dismissed contact.
            assertThat(contacts.observeContactCounts().first()).isEmpty()
        }

        @Test
        @DisplayName("dismissal is keyed case- and whitespace-insensitively")
        fun `dismissal matches regardless of casing`() = runTest {
            storeContact("doc-1", "Frau Müller")
            linker.dismiss("doc-1", "  FRAU MÜLLER  ")

            linker.process("doc-1", jobcenterLetter())

            assertThat(profiles().none { it.name == "Frau Müller" }).isTrue()
            assertThat(contacts.observeContactCounts().first()).isEmpty()
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("A caseworker with no sender entity in the document")
    inner class OrphanContact {

        @Test
        @DisplayName("never becomes a profile, and waits on the letter")
        fun `a contact with unknown organisation is never auto-created`() = runTest {
            storeContact("doc-1", "Frau Müller")

            val outcome = linker.process(
                "doc-1",
                DocumentUnderstanding(
                    entities = listOf(
                        entity("Frau Müller", EntityKind.PERSON, EntityRole.SENDER_CONTACT),
                    ),
                ),
            )

            assertThat(outcome.created).isEqualTo(0)
            assertThat(profiles()).isEmpty()
            assertThat(contacts.observeContactCounts().first()).isEmpty()
            assertThat(outcome.contact).isEqualTo(ContactLinkOutcome.Pending(PendingReason.SENDER_UNRESOLVED))
        }

        @Test
        @DisplayName("is attached when a later reading of the letter resolves the sender")
        fun `a waiting contact is attached once the sender is linked`() = runTest {
            storeContact("doc-1", "Frau Müller")
            linker.process("doc-1", DocumentUnderstanding(entities = listOf(entity("Frau Müller", EntityKind.PERSON, EntityRole.SENDER_CONTACT))))

            val outcome = linker.process("doc-1", jobcenterLetter())

            val jobcenter = profiles().single { it.kind == ProfileKind.ORGANISATION }
            assertThat(contacts.observeContacts(jobcenter.id).first().map { it.name }).containsExactly("Frau Müller")
            assertThat(outcome.contact).isInstanceOf(ContactLinkOutcome.Created::class.java)
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Failure isolation")
    inner class FailureIsolation {

        @Test
        @DisplayName("one entity's profile failure does not stop the rest of the document")
        fun `a repository failure on one entity is not fatal to the others`() = runTest {
            profileRepository.failWith = com.postsaimanager.core.common.result.PamError
                .DatabaseError(IllegalStateException("boom"))

            // process() must not throw even when every write fails — the caller
            // (DocumentProcessingPipeline) treats an exception here as a reason to fail the
            // whole document, which profile linking must never do.
            val outcome = linker.process("doc-1", jobcenterLetter())

            assertThat(outcome.created).isEqualTo(0)
            assertThat(outcome.linked).isEqualTo(0)
        }
    }
}

/** In-memory [DismissedEntityDao] — no Room needed, this is a plain interface to implement. */
private class FakeDismissedEntityDao : DismissedEntityDao {
    private val dismissed = mutableSetOf<Pair<String, String>>()

    override suspend fun dismiss(entity: DismissedEntityEntity) {
        dismissed += entity.documentId to entity.entityName
    }

    override suspend fun isDismissed(documentId: String, entityName: String): Boolean =
        (documentId to entityName) in dismissed
}
