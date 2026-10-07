package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
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
 * equally high confidence — the model has no trouble with any of the names — yet only the sender
 * should ever produce a profile on its own. Confidence says the reading is trustworthy;
 * it says nothing about whether the app should be tracking that person at all. Role is what
 * decides that, and these tests pin each row so a "just check confidence" refactor cannot
 * quietly start creating a profile for someone's spouse. The contact person (Frau Müller) is never
 * a profile: she is a contact of the sender's organisation, attached by the contact linking.
 *
 * Changes with the contacts phase 2: the decision no longer takes the sender organisation (only the old
 * contact branch used it), a match says whether it is "Me" and what kind it is instead of the old one-axis
 * type, and the SENDER_CONTACT rows are replaced by "never a profile".
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
        kind: ProfileKind = ProfileKind.ORGANISATION,
        self: Boolean = false,
        id: String = "profile-1",
    ) = EntityLinkingUseCase.MatchCandidate(profileId = id, kind = kind, isSelf = self, isExactMatch = exact)

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("SENDER — Jobcenter Berlin Mitte")
    inner class Sender {

        @Test
        @DisplayName("a confident reading with no existing profile creates an organisation")
        fun `first letter from a sender creates a profile`() {
            val action = decide.decide(
                entity(role = EntityRole.SENDER, confidence = 0.9f),
                match = null,
                isDismissed = false,
            )

            assertThat(action).isEqualTo(
                EntityLinkingUseCase.Action.Create(
                    ProfileKind.ORGANISATION, "Jobcenter Berlin Mitte", "Jobcenter Berlin Mitte",
                    ProfileRole.SENDER,
                ),
            )
        }

        @Test
        @DisplayName("a second letter from the same sender links instead of duplicating")
        fun `an exact match links rather than creating a second profile`() {
            val action = decide.decide(
                entity(role = EntityRole.SENDER, confidence = 0.9f),
                match = match(exact = true),
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
                match = match(exact = false),
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
        @DisplayName("links to an existing \"Me\" profile")
        fun `a confident match to Me auto-links`() {
            val action = decide.decide(
                entity(name = "Sam", kind = EntityKind.PERSON, role = EntityRole.RECIPIENT, confidence = 0.9f),
                match = match(exact = true, kind = ProfileKind.PERSON, self = true),
                isDismissed = false,
            )

            assertThat(action).isEqualTo(EntityLinkingUseCase.Action.Link("profile-1", ProfileRole.RECEIVER))
        }

        @Test
        @DisplayName("is never silently created, even when read perfectly and nothing else matches")
        fun `no Me profile yet means propose, never create`() {
            // This is the "who am I" case. Getting it wrong pollutes every future document,
            // which one deleted profile cannot undo — so it may never be decided by the app.
            val action = decide.decide(
                entity(name = "Sam", kind = EntityKind.PERSON, role = EntityRole.RECIPIENT, confidence = 0.99f),
                match = null,
                isDismissed = false,
            )

            assertThat(action).isInstanceOf(EntityLinkingUseCase.Action.Propose::class.java)
            val proposal = action as EntityLinkingUseCase.Action.Propose
            // The proposal is about the "Me" role of a person (the old test read the one-axis type USER_SELF).
            assertThat(proposal.kind).isEqualTo(ProfileKind.PERSON)
            assertThat(proposal.householdRole).isEqualTo(HouseholdRole.SELF)
        }

        @Test
        @DisplayName("matching an ordinary contact by name does not make them \"Me\"")
        fun `a confident match to a non-self profile still proposes`() {
            // A recipient's name happening to match an existing PERSON profile (a namesake,
            // a family member already on file) must not be silently promoted to "this is me".
            val action = decide.decide(
                entity(name = "Sam", kind = EntityKind.PERSON, role = EntityRole.RECIPIENT, confidence = 0.9f),
                match = match(exact = true, kind = ProfileKind.PERSON, self = false),
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
        @DisplayName("is never a profile: it is attached to the sender's organisation as a contact")
        fun `a contact is attached, never created as a profile`() {
            // Replaces "a contact is created as a PERSON with the organisation attached" (phase 1 left it as a profile).
            val action = decide.decide(
                entity(name = "Frau Müller", kind = EntityKind.PERSON, role = EntityRole.SENDER_CONTACT, confidence = 0.9f),
                match = null,
                isDismissed = false,
            )

            assertThat(action).isEqualTo(EntityLinkingUseCase.Action.AttachContact)
        }

        @Test
        @DisplayName("even a profile of the same name on file does not make the contact a profile link")
        fun `a namesake profile is not linked as the contact`() {
            // Replaces "an exact match links to her existing profile as CASE_WORKER": the old caseworker profiles
            // were moved into organisations (or stay as they are), but a new letter never links one again.
            val action = decide.decide(
                entity(name = "Frau Müller", kind = EntityKind.PERSON, role = EntityRole.SENDER_CONTACT, confidence = 0.9f),
                match = match(exact = true, kind = ProfileKind.PERSON),
                isDismissed = false,
            )

            assertThat(action).isEqualTo(EntityLinkingUseCase.Action.AttachContact)
        }

        @Test
        @DisplayName("a contact no one reads confidently is still not a profile")
        fun `a low confidence contact is not proposed as a profile either`() {
            // Replaces "a signature with no sender entity is proposed, not created": the confidence of the contact
            // and the sender being resolved are checked by the contact linking, which keeps it waiting on the letter.
            val action = decide.decide(
                entity(name = "Frau Müller", kind = EntityKind.PERSON, role = EntityRole.SENDER_CONTACT, confidence = 0.2f),
                match = null,
                isDismissed = false,
            )

            assertThat(action).isEqualTo(EntityLinkingUseCase.Action.AttachContact)
        }

        @Test
        @DisplayName("a contact the user removed from this letter is ignored")
        fun `a dismissed contact is ignored`() {
            val action = decide.decide(
                entity(name = "Frau Müller", kind = EntityKind.PERSON, role = EntityRole.SENDER_CONTACT, confidence = 0.95f),
                match = null,
                isDismissed = true,
            )

            assertThat(action).isEqualTo(EntityLinkingUseCase.Action.Ignore)
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
                isDismissed = false,
            )

            assertThat(action).isInstanceOf(EntityLinkingUseCase.Action.Propose::class.java)
        }

        @Test
        @DisplayName("a mentioned person already on file still links")
        fun `an exact match to an existing profile auto-links`() {
            val action = decide.decide(
                entity(name = "Layla", kind = EntityKind.PERSON, role = EntityRole.MENTIONED, confidence = 0.9f),
                match = match(exact = true, kind = ProfileKind.PERSON),
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
                isDismissed = true,
            )

            assertThat(action).isEqualTo(EntityLinkingUseCase.Action.Ignore)
        }
    }
}
