package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.RecognisedEntity
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [EntityLinkingUseCase] — the decision table that turns a recognised entity into a
 * link, a new profile, a proposal, or nothing.
 *
 * The scenario this whole class exists for: a letter from Jobcenter Berlin Mitte, signed by
 * Frau Müller, addressed to Sam, mentioning his wife Layla. All four entities can be read with
 * equally high confidence — the model has no trouble with any of the names — yet only two of
 * them should ever produce a profile on their own. Confidence says the reading is trustworthy;
 * it says nothing about whether the app should be tracking that person at all. Role is what
 * decides that, and these tests pin each row so a "just check confidence" refactor cannot
 * quietly start creating a profile for someone's spouse.
 */
class EntityLinkingUseCaseTest {

    private val decide = EntityLinkingUseCase()

    private fun entity(
        name: String = "Jobcenter Berlin Mitte",
        kind: EntityKind = EntityKind.AUTHORITY,
        role: EntityRole = EntityRole.SENDER,
        confidence: Float = 0.9f,
    ) = RecognisedEntity(name = name, kind = kind, role = role, confidence = confidence)

    private fun match(
        exact: Boolean = true,
        type: ProfileType = ProfileType.AUTHORITY,
        id: String = "profile-1",
    ) = EntityLinkingUseCase.MatchCandidate(profileId = id, profileType = type, isExactMatch = exact)

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("SENDER — Jobcenter Berlin Mitte")
    inner class Sender {

        @Test
        @DisplayName("a confident reading with no existing profile creates an AUTHORITY")
        fun `first letter from a sender creates a profile`() {
            val action = decide.decide(
                entity(role = EntityRole.SENDER, confidence = 0.9f),
                match = null,
                senderOrganisation = null,
                isDismissed = false,
            )

            assertThat(action).isEqualTo(
                EntityLinkingUseCase.Action.Create(
                    ProfileType.AUTHORITY, "Jobcenter Berlin Mitte", "Jobcenter Berlin Mitte",
                    ProfileRole.SENDER,
                ),
            )
        }

        @Test
        @DisplayName("a second letter from the same sender links instead of duplicating")
        fun `an exact match links rather than creating a second profile`() {
            val action = decide.decide(
                entity(role = EntityRole.SENDER, confidence = 0.9f),
                match = match(exact = true, type = ProfileType.AUTHORITY),
                senderOrganisation = null,
                isDismissed = false,
            )

            assertThat(action).isEqualTo(EntityLinkingUseCase.Action.Link("profile-1", ProfileRole.SENDER))
        }

        @Test
        @DisplayName("BUG this test guards against: a half-read sender name must not create a profile")
        fun `a low confidence reading is proposed, not created`() {
            val action = decide.decide(
                entity(role = EntityRole.SENDER, confidence = 0.4f),
                match = null,
                senderOrganisation = null,
                isDismissed = false,
            )

            assertThat(action).isInstanceOf(EntityLinkingUseCase.Action::class.java)
            assertThat(action).isInstanceOf(EntityLinkingUseCase.Action.Propose::class.java)
        }

        @Test
        @DisplayName("a weak name resemblance to an existing profile is confirmed, not merged blindly")
        fun `a possible but unconfident match does not auto-link`() {
            // "Jobcenter Berlin Mitte" weakly resembling "Jobcenter Hamburg" (both contain
            // "Jobcenter") must not silently file this letter under the wrong branch office.
            val action = decide.decide(
                entity(role = EntityRole.SENDER, confidence = 0.9f),
                match = match(exact = false, type = ProfileType.AUTHORITY),
                senderOrganisation = null,
                isDismissed = false,
            )

            assertThat(action).isInstanceOf(EntityLinkingUseCase.Action.Propose::class.java)
            val proposal = action as EntityLinkingUseCase.Action.Propose
            // The near-match still travels with the proposal so the UI can offer it.
            assertThat(proposal.existingProfileId).isEqualTo("profile-1")
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("RECIPIENT — Sam")
    inner class Recipient {

        @Test
        @DisplayName("links to an existing USER_SELF profile")
        fun `a confident match to USER_SELF auto-links`() {
            val action = decide.decide(
                entity(name = "Sam", kind = EntityKind.PERSON, role = EntityRole.RECIPIENT, confidence = 0.9f),
                match = match(exact = true, type = ProfileType.USER_SELF),
                senderOrganisation = null,
                isDismissed = false,
            )

            assertThat(action).isEqualTo(EntityLinkingUseCase.Action.Link("profile-1", ProfileRole.RECEIVER))
        }

        @Test
        @DisplayName("is never silently created, even when read perfectly and nothing else matches")
        fun `no USER_SELF profile yet means propose, never create`() {
            // This is the "who am I" case. Getting it wrong pollutes every future document,
            // which one deleted profile cannot undo — so it may never be decided by the app.
            val action = decide.decide(
                entity(name = "Sam", kind = EntityKind.PERSON, role = EntityRole.RECIPIENT, confidence = 0.99f),
                match = null,
                senderOrganisation = null,
                isDismissed = false,
            )

            assertThat(action).isInstanceOf(EntityLinkingUseCase.Action.Propose::class.java)
            val proposal = action as EntityLinkingUseCase.Action.Propose
            assertThat(proposal.profileType).isEqualTo(ProfileType.USER_SELF)
        }

        @Test
        @DisplayName("matching an ordinary contact by name does not make them USER_SELF")
        fun `a confident match to a non-self profile still proposes`() {
            // A recipient's name happening to match an existing PERSON profile (a namesake,
            // a family member already on file) must not be silently promoted to "this is me".
            val action = decide.decide(
                entity(name = "Sam", kind = EntityKind.PERSON, role = EntityRole.RECIPIENT, confidence = 0.9f),
                match = match(exact = true, type = ProfileType.PERSON),
                senderOrganisation = null,
                isDismissed = false,
            )

            assertThat(action).isInstanceOf(EntityLinkingUseCase.Action.Propose::class.java)
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("SENDER_CONTACT — Frau Müller")
    inner class SenderContact {

        @Test
        @DisplayName("becomes a PERSON at the sender's organisation, not a free-floating contact")
        fun `a contact is created with the sender organisation attached`() {
            val action = decide.decide(
                entity(name = "Frau Müller", kind = EntityKind.PERSON, role = EntityRole.SENDER_CONTACT, confidence = 0.9f),
                match = null,
                senderOrganisation = "Jobcenter Berlin Mitte",
                isDismissed = false,
            )

            assertThat(action).isEqualTo(
                EntityLinkingUseCase.Action.Create(
                    ProfileType.PERSON, "Frau Müller", "Jobcenter Berlin Mitte", ProfileRole.CASE_WORKER,
                ),
            )
        }

        @Test
        @DisplayName("a second letter with the same caseworker links to her existing profile")
        fun `an exact match links instead of duplicating the caseworker`() {
            val action = decide.decide(
                entity(name = "Frau Müller", kind = EntityKind.PERSON, role = EntityRole.SENDER_CONTACT, confidence = 0.9f),
                match = match(exact = true, type = ProfileType.PERSON),
                senderOrganisation = "Jobcenter Berlin Mitte",
                isDismissed = false,
            )

            assertThat(action).isEqualTo(EntityLinkingUseCase.Action.Link("profile-1", ProfileRole.CASE_WORKER))
        }

        @Test
        @DisplayName("a signature with no sender entity in the document is proposed, not created")
        fun `an orphan contact with unknown organisation is never auto-created`() {
            // Exactly the clutter rule 1 warns about: a name from a signature line, with no
            // sender entity in the document to attach it to, would otherwise become a
            // free-floating person profile nobody asked for.
            val action = decide.decide(
                entity(name = "Frau Müller", kind = EntityKind.PERSON, role = EntityRole.SENDER_CONTACT, confidence = 0.95f),
                match = null,
                senderOrganisation = null,
                isDismissed = false,
            )

            assertThat(action).isInstanceOf(EntityLinkingUseCase.Action.Propose::class.java)
            val proposal = action as EntityLinkingUseCase.Action.Propose
            assertThat(proposal.organization).isNull()
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("MENTIONED — Layla")
    inner class Mentioned {

        @Test
        @DisplayName("a perfectly read spouse is proposed, never created")
        fun `mentioned people are never auto-created regardless of confidence`() {
            // The case the whole class doc opens with: 0.95 confidence, correctly read, and
            // still not a reason to create a profile on its own.
            val action = decide.decide(
                entity(
                    name = "Layla",
                    kind = EntityKind.PERSON,
                    role = EntityRole.MENTIONED,
                    confidence = 0.95f,
                ),
                match = null,
                senderOrganisation = "Jobcenter Berlin Mitte",
                isDismissed = false,
            )

            assertThat(action).isInstanceOf(EntityLinkingUseCase.Action.Propose::class.java)
        }

        @Test
        @DisplayName("a mentioned person already on file still links")
        fun `an exact match to an existing profile auto-links`() {
            val action = decide.decide(
                entity(name = "Layla", kind = EntityKind.PERSON, role = EntityRole.MENTIONED, confidence = 0.9f),
                match = match(exact = true, type = ProfileType.FAMILY_MEMBER),
                senderOrganisation = null,
                isDismissed = false,
            )

            assertThat(action).isEqualTo(EntityLinkingUseCase.Action.Link("profile-1", ProfileRole.RELATED))
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Dismissal")
    inner class Dismissal {

        @Test
        @DisplayName("a dismissed entity is ignored even when it would otherwise auto-create")
        fun `dismissal overrides every other branch`() {
            // Without this, reprocessing a document would recreate exactly what the user
            // just deleted or said no to — arguing with them once per scan, forever.
            val action = decide.decide(
                entity(role = EntityRole.SENDER, confidence = 0.99f),
                match = null,
                senderOrganisation = null,
                isDismissed = true,
            )

            assertThat(action).isEqualTo(EntityLinkingUseCase.Action.Ignore)
        }

        @Test
        @DisplayName("dismissal overrides even a confident exact match")
        fun `a dismissed entity is not relinked either`() {
            val action = decide.decide(
                entity(role = EntityRole.MENTIONED, confidence = 0.99f),
                match = match(exact = true),
                senderOrganisation = null,
                isDismissed = true,
            )

            assertThat(action).isEqualTo(EntityLinkingUseCase.Action.Ignore)
        }
    }
}
