package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.database.dao.DismissedEntityDao
import com.postsaimanager.core.data.database.entity.DismissedEntityEntity
import com.postsaimanager.core.domain.usecase.EntityLinkingUseCase
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.RecognisedEntity
import com.postsaimanager.core.testing.FakeProfileRepository
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
 * addressed to Sam, mentioning his wife Layla — the exact case task 7.14.11 exists to serve.
 */
class EntityProfileLinkerTest {

    private val profileRepository = FakeProfileRepository()
    private val dismissedDao = FakeDismissedEntityDao()
    private val matcher = ProfileMatcher(profileRepository)
    private val linker = EntityProfileLinker(matcher, profileRepository, dismissedDao, EntityLinkingUseCase())

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

            assertThat(outcome.created).isEqualTo(2) // Jobcenter + Frau Müller
            val jobcenter = profiles()
                .single { it.organization == "Jobcenter Berlin Mitte" && it.type == ProfileType.AUTHORITY }
            assertThat(profileRepository.links).contains(
                Triple(jobcenter.id, "doc-1", ProfileRole.SENDER),
            )
        }

        @Test
        @DisplayName("the caseworker is a PERSON at that organisation, not a free-floating contact")
        fun `the sender contact is created with the sender organisation attached`() = runTest {
            linker.process("doc-1", jobcenterLetter())

            val mueller = profiles().single { it.name == "Frau Müller" }
            assertThat(mueller.type).isEqualTo(ProfileType.PERSON)
            assertThat(mueller.organization).isEqualTo("Jobcenter Berlin Mitte")
            assertThat(profileRepository.links).contains(
                Triple(mueller.id, "doc-1", ProfileRole.CASE_WORKER),
            )
        }

        @Test
        @DisplayName("Sam is never created, only ever proposed, with no USER_SELF profile yet")
        fun `the recipient is proposed rather than created`() = runTest {
            val outcome = linker.process("doc-1", jobcenterLetter())

            assertThat(profiles().none { it.name == "Sam" }).isTrue()
            assertThat(outcome.proposals.map { it.entityName }).contains("Sam")
            val samProposal = outcome.proposals.single { it.entityName == "Sam" }
            assertThat(samProposal.profileType).isEqualTo(ProfileType.USER_SELF)
        }

        @Test
        @DisplayName("Layla is proposed, never created, even read with high confidence")
        fun `a mentioned family member is proposed rather than created`() = runTest {
            val layla = entity("Layla", EntityKind.PERSON, EntityRole.MENTIONED, confidence = 0.95f)

            val outcome = linker.process("doc-1", jobcenterLetter(layla))

            assertThat(profiles().none { it.name == "Layla" }).isTrue()
            assertThat(outcome.proposals.map { it.entityName }).contains("Layla")
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
                .single { it.organization == "Jobcenter Berlin Mitte" && it.type == ProfileType.AUTHORITY }
            assertThat(profileRepository.links).contains(
                Triple(jobcenter.id, "doc-2", ProfileRole.SENDER),
            )
        }

        @Test
        @DisplayName("the same caseworker on a second letter links rather than duplicating")
        fun `an existing contact at the same organisation is linked, not recreated`() = runTest {
            linker.process("doc-1", jobcenterLetter())

            val outcome = linker.process(
                "doc-2",
                DocumentUnderstanding(
                    entities = listOf(
                        entity("Jobcenter Berlin Mitte", EntityKind.AUTHORITY, EntityRole.SENDER),
                        entity("Frau Müller", EntityKind.PERSON, EntityRole.SENDER_CONTACT),
                    ),
                ),
            )

            assertThat(outcome.created).isEqualTo(0)
            assertThat(profiles().count { it.name == "Frau Müller" }).isEqualTo(1)
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("RECIPIENT linking to an existing USER_SELF")
    inner class RecipientLinking {

        @Test
        fun `links to the existing USER_SELF profile instead of proposing`() = runTest {
            val me = testProfile(id = "me", name = "Sam", type = ProfileType.USER_SELF)
            profileRepository.seed(me)

            val outcome = linker.process("doc-1", jobcenterLetter())

            assertThat(outcome.proposals.none { it.entityName == "Sam" }).isTrue()
            assertThat(profileRepository.links).contains(Triple("me", "doc-1", ProfileRole.RECEIVER))
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Dismissal sticks across reprocessing")
    inner class DismissalSticks {

        @Test
        @DisplayName("a dismissed contact is not recreated when the document is reprocessed")
        fun `a dismissed proposal is ignored on the next run`() = runTest {
            // The user saw "Frau Müller — create a contact?" with no sender org known yet
            // (an earlier, sparser reading of the same letter) and said no.
            linker.dismiss("doc-1", "Frau Müller")

            val outcome = linker.process("doc-1", jobcenterLetter())

            assertThat(profiles().none { it.name == "Frau Müller" }).isTrue()
            assertThat(outcome.proposals.none { it.entityName == "Frau Müller" }).isTrue()
        }

        @Test
        @DisplayName("dismissal is keyed case- and whitespace-insensitively")
        fun `dismissal matches regardless of casing`() = runTest {
            linker.dismiss("doc-1", "  FRAU MÜLLER  ")

            linker.process("doc-1", jobcenterLetter())

            assertThat(profiles().none { it.name == "Frau Müller" }).isTrue()
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("A caseworker with no sender entity in the document")
    inner class OrphanContact {

        @Test
        @DisplayName("is proposed rather than becoming a free-floating person profile")
        fun `a contact with unknown organisation is never auto-created`() = runTest {
            val outcome = linker.process(
                "doc-1",
                DocumentUnderstanding(
                    entities = listOf(
                        entity("Frau Müller", EntityKind.PERSON, EntityRole.SENDER_CONTACT),
                    ),
                ),
            )

            assertThat(outcome.created).isEqualTo(0)
            assertThat(outcome.proposals).hasSize(1)
            assertThat(outcome.proposals.single().organization).isNull()
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
