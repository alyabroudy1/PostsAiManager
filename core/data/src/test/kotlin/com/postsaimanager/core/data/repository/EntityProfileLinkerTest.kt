package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.DismissedEntityDao
import com.postsaimanager.core.data.database.dao.EntityProposalDao
import com.postsaimanager.core.data.database.entity.DismissedEntityEntity
import com.postsaimanager.core.data.database.entity.EntityProposalEntity
import com.postsaimanager.core.domain.usecase.EntityLinkingUseCase
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.RecognisedEntity
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val proposalDao = FakeEntityProposalDao()
    private val matcher = ProfileMatcher(profileRepository)
    private val linker = EntityProfileLinker(
        matcher, profileRepository, dismissedDao, proposalDao, EntityLinkingUseCase(),
    )

    private fun entity(
        name: String,
        kind: EntityKind,
        role: EntityRole,
        confidence: Float = 0.9f,
        relation: String = "",
    ) = RecognisedEntity(name = name, kind = kind, role = role, relation = relation, confidence = confidence)

    private suspend fun profiles() = profileRepository.getProfiles().first()

    private suspend fun pendingProposals(documentId: String) =
        linker.pendingProposals(documentId).first()

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

    // ═══════════════════════════════════════════════════════════
    // Task 7.14.11b: Propose results used to be counted in a log line and dropped. These pin
    // that a proposal now survives, and that answering it is idempotent and final.
    @Nested
    @DisplayName("Persisted proposals reach the user (task 7.14.11b)")
    inner class PersistedProposals {

        @Test
        @DisplayName("the gap this whole task exists to close: Sam was proposed, then forgotten")
        fun `a proposal survives to be read back`() = runTest {
            linker.process("doc-1", jobcenterLetter())

            val pending = pendingProposals("doc-1")

            assertThat(pending.map { it.entityName }).contains("Sam")
        }

        @Test
        @DisplayName("accepting Layla creates her profile, links it as RELATED, and resolves it")
        fun `accepting creates and links and resolves the proposal`() = runTest {
            val layla = entity("Layla", EntityKind.PERSON, EntityRole.MENTIONED, confidence = 0.95f)
            linker.process("doc-1", jobcenterLetter(layla))
            val proposal = pendingProposals("doc-1").single { it.entityName == "Layla" }

            val result = linker.accept(proposal)

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            val laylaProfile = profiles().single { it.name == "Layla" }
            assertThat(profileRepository.links).contains(
                Triple(laylaProfile.id, "doc-1", ProfileRole.RELATED),
            )
            // Answered once, not askable again — the whole point of "resolved".
            assertThat(pendingProposals("doc-1").none { it.entityName == "Layla" }).isTrue()
        }

        /**
         * A second letter names the sender slightly differently — close enough that
         * [ProfileMatcher] surfaces the existing Jobcenter profile as a candidate, not close
         * enough (0.8, below `EXACT_MATCH_CONFIDENCE`) to auto-link — so the app proposes
         * rather than guesses, carrying that profile's id as
         * [EntityProposal.existingProfileId].
         */
        private suspend fun proposeWeakJobcenterMatch() {
            linker.process(
                "doc-2",
                DocumentUnderstanding(
                    entities = listOf(
                        entity("Jobcenter Berlin", EntityKind.AUTHORITY, EntityRole.SENDER),
                    ),
                ),
            )
        }

        @Test
        @DisplayName(
            "the user's \"yes\" to a known organisation must link it, not file a second copy " +
                "of the same Jobcenter",
        )
        fun `accepting links to an existing profile instead of duplicating it`() = runTest {
            // First letter creates the one and only "Jobcenter Berlin Mitte" profile. Filter
            // on type too — Frau Müller's PERSON profile also carries this as `organization`.
            linker.process("doc-1", jobcenterLetter())
            val jobcenter = profiles()
                .single { it.organization == "Jobcenter Berlin Mitte" && it.type == ProfileType.AUTHORITY }

            proposeWeakJobcenterMatch()
            val proposal = pendingProposals("doc-2").single { it.entityName == "Jobcenter Berlin" }
            assertThat(proposal.existingProfileId).isEqualTo(jobcenter.id)
            val profileCountBefore = profiles().size

            val result = linker.accept(proposal)

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            // The whole propose-don't-create design exists to prevent a second
            // "Jobcenter Berlin Mitte" row — a duplicate created here, right after the user
            // confirmed the app's own guess, would be the least excusable version of the bug
            // it is meant to stop.
            assertThat(profiles()).hasSize(profileCountBefore)
            assertThat(profileRepository.links).contains(
                Triple(jobcenter.id, "doc-2", proposal.role),
            )
            val stillPending = pendingProposals("doc-2").any { it.entityName == "Jobcenter Berlin" }
            assertThat(stillPending).isFalse()
        }

        @Test
        @DisplayName("a proposal that never matched anything still creates a profile, unchanged")
        fun `accepting with no existing profile still creates`() = runTest {
            // An unrelated profile is on file so a bug that links to "whatever exists" rather
            // than "nothing matched" would be caught.
            profileRepository.seed(testProfile(id = "other", name = "Unrelated Authority"))
            val layla = entity("Layla", EntityKind.PERSON, EntityRole.MENTIONED, confidence = 0.95f)
            linker.process("doc-1", jobcenterLetter(layla))
            val proposal = pendingProposals("doc-1").single { it.entityName == "Layla" }
            assertThat(proposal.existingProfileId).isNull()

            val result = linker.accept(proposal)

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            val laylaProfile = profiles().single { it.name == "Layla" }
            assertThat(profileRepository.links).contains(
                Triple(laylaProfile.id, "doc-1", ProfileRole.RELATED),
            )
            assertThat(profileRepository.links.none { it.first == "other" }).isTrue()
        }

        @Test
        @DisplayName(
            "the matched profile is deleted between the proposal being raised and the user " +
                "answering it — accepting must not surface that as an error",
        )
        fun `accepting falls back to create when the existing profile was deleted`() = runTest {
            linker.process("doc-1", jobcenterLetter())
            val jobcenter = profiles()
                .single { it.organization == "Jobcenter Berlin Mitte" && it.type == ProfileType.AUTHORITY }

            proposeWeakJobcenterMatch()
            val proposal = pendingProposals("doc-2").single { it.entityName == "Jobcenter Berlin" }
            assertThat(proposal.existingProfileId).isEqualTo(jobcenter.id)

            // The profile the proposal pointed at is gone by the time the user answers — e.g.
            // the user deleted it in the meantime.
            profileRepository.deleteProfile(jobcenter.id)

            val result = linker.accept(proposal)

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            val created = profiles().single { it.name == "Jobcenter Berlin" }
            assertThat(created.id).isNotEqualTo(jobcenter.id)
            assertThat(profileRepository.links).contains(Triple(created.id, "doc-2", proposal.role))
            val stillPending = pendingProposals("doc-2").any { it.entityName == "Jobcenter Berlin" }
            assertThat(stillPending).isFalse()
        }

        @Test
        @DisplayName("dismissing Sam tombstones him, so reprocessing the letter does not ask again")
        fun `dismissing tombstones the entity and reprocessing does not re-propose`() = runTest {
            linker.process("doc-1", jobcenterLetter())
            val sam = pendingProposals("doc-1").single { it.entityName == "Sam" }

            linker.dismiss(sam)
            val outcome = linker.process("doc-1", jobcenterLetter())

            assertThat(outcome.proposals.none { it.entityName == "Sam" }).isTrue()
            assertThat(pendingProposals("doc-1").none { it.entityName == "Sam" }).isTrue()
            assertThat(profiles().none { it.name == "Sam" }).isTrue()
        }

        @Test
        @DisplayName("scanning the same letter twice must not turn one question into two")
        fun `reprocessing twice does not duplicate a still-pending proposal`() = runTest {
            linker.process("doc-1", jobcenterLetter())
            linker.process("doc-1", jobcenterLetter())

            val samProposals = pendingProposals("doc-1").filter { it.entityName == "Sam" }

            assertThat(samProposals).hasSize(1)
        }

        @Test
        @DisplayName("a name already dismissed never resurfaces, not even as a fresh proposal row")
        fun `a dismissed entity is never persisted as a proposal in the first place`() = runTest {
            linker.dismiss("doc-1", "Layla")
            val layla = entity("Layla", EntityKind.PERSON, EntityRole.MENTIONED, confidence = 0.95f)

            linker.process("doc-1", jobcenterLetter(layla))

            assertThat(pendingProposals("doc-1").none { it.entityName == "Layla" }).isTrue()
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

/**
 * In-memory [EntityProposalDao]. Mirrors the real DAO's `OnConflictStrategy.IGNORE` on the
 * (documentId, entityNameKey) unique index, which is the mechanism reprocessing relies on to
 * not duplicate a still-pending proposal.
 */
private class FakeEntityProposalDao : EntityProposalDao {
    private val rows = MutableStateFlow<List<EntityProposalEntity>>(emptyList())

    override suspend fun insert(proposal: EntityProposalEntity) {
        val conflict = rows.value.any {
            it.documentId == proposal.documentId && it.entityNameKey == proposal.entityNameKey
        }
        if (!conflict) rows.value = rows.value + proposal
    }

    override fun getForDocument(documentId: String): Flow<List<EntityProposalEntity>> =
        rows.map { list -> list.filter { it.documentId == documentId }.sortedBy { it.createdAt } }

    override suspend fun delete(id: String) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}
