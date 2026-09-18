package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityProposal
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.MatchType
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileSuggestion
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.testing.testProfile
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [EntityCoverageFilter].
 *
 * The behaviour under test is task 7.14.11d: a document detail screen that could show two
 * differently-worded, differently-buttoned cards asking about the same person — one from
 * `ProfileMatchingService` (extracted fields), one from `EntityProposalService` (recognised
 * entities). Each test below pins one rule of when the field-based card must disappear, and —
 * just as important — when it must not, because the entity path is not a strict superset of
 * the field path.
 */
class EntityCoverageFilterTest {

    private val documentId = "doc-1"

    private fun suggestion(
        role: ProfileRole = ProfileRole.SENDER,
        extractedName: String? = null,
        extractedOrganization: String? = null,
        matchType: MatchType = MatchType.NEW_PROFILE,
    ) = ProfileSuggestion(
        role = role,
        matchType = matchType,
        existingProfile = null,
        confidence = 0f,
        extractedName = extractedName,
        extractedOrganization = extractedOrganization,
        extractedEmail = null,
        extractedPhone = null,
        extractedAddress = null,
        documentId = documentId,
        isAutoLinked = false,
    )

    private fun proposal(
        entityName: String,
        organization: String? = null,
        role: ProfileRole = ProfileRole.SENDER,
    ) = EntityProposal(
        id = "proposal-$entityName",
        documentId = documentId,
        entityName = entityName,
        kind = EntityKind.AUTHORITY,
        entityRole = EntityRole.SENDER,
        relation = "",
        role = role,
        profileType = ProfileType.AUTHORITY,
        organization = organization,
        existingProfileId = null,
        confidence = 0.9f,
        createdAt = 0L,
    )

    @Nested
    @DisplayName("Coverage from a pending entity proposal")
    inner class FromProposal {

        @Test
        @DisplayName("a field suggestion for a person the entity path already proposed is hidden")
        fun `hidden when an entity proposal already names the same organisation`() {
            // The reported symptom: a Jobcenter letter put up a "Sender Organization" card
            // next to an EntityProposal card, both about the same Jobcenter, worded
            // differently, with different buttons.
            val fieldSuggestion = suggestion(extractedOrganization = "Jobcenter Berlin")
            val entityProposal = proposal(entityName = "Jobcenter Berlin")

            val result = EntityCoverageFilter.apply(
                listOf(fieldSuggestion), listOf(entityProposal), emptyList(),
            )

            assertThat(result).isEmpty()
        }

        @Test
        @DisplayName("an unrelated suggestion survives alongside a covered one")
        fun `only the covered suggestion is removed, not the whole list`() {
            val covered = suggestion(
                role = ProfileRole.SENDER, extractedOrganization = "Jobcenter Berlin",
            )
            val unrelated = suggestion(
                role = ProfileRole.RECEIVER, extractedName = "Aylin Mustermann",
            )
            val entityProposal = proposal(entityName = "Jobcenter Berlin")

            val result = EntityCoverageFilter.apply(
                listOf(covered, unrelated), listOf(entityProposal), emptyList(),
            )

            assertThat(result).containsExactly(unrelated)
        }
    }

    @Nested
    @DisplayName("Coverage from an already-linked profile")
    inner class FromLinkedProfile {

        @Test
        @DisplayName("a field suggestion for a person the entity path already linked is hidden")
        fun `hidden when the entity path already created and linked a profile for this document`() {
            // EntityLinkingUseCase.Action.Link / Create resolve silently during processing —
            // no EntityProposal is ever raised for them, so pendingProposals alone would miss
            // this case entirely and the duplicate card would still appear.
            val fieldSuggestion = suggestion(extractedOrganization = "Jobcenter Berlin")
            val linkedByEntityPath = testProfile(name = "Jobcenter Berlin")
                .copy(sourceDocumentId = documentId, sourceEntityName = "jobcenter berlin")

            val result = EntityCoverageFilter.apply(
                listOf(fieldSuggestion), emptyList(), listOf(linkedByEntityPath),
            )

            assertThat(result).isEmpty()
        }

        @Test
        @DisplayName("a profile linked here but sourced from a different document IS coverage")
        fun `a profile this document did not itself machine-create is still entity coverage`() {
            // sourceDocumentId records which document CREATED the profile (Profile's class
            // doc), not every document it has since been linked to — and it is deliberately
            // NOT consulted here. Do not restore a `sourceDocumentId == documentId` check: it
            // looks like a safety net but it is the bug this test pins against. See
            // `second letter from the same sender is suppressed too` below for the concrete
            // real-world scenario this covers.
            val fieldSuggestion = suggestion(extractedOrganization = "Jobcenter Berlin")
            val fromAnotherDocument = testProfile(name = "Jobcenter Berlin")
                .copy(sourceDocumentId = "doc-other", sourceEntityName = "jobcenter berlin")

            val result = EntityCoverageFilter.apply(
                listOf(fieldSuggestion), emptyList(), listOf(fromAnotherDocument),
            )

            assertThat(result).isEmpty()
        }

        @Test
        @DisplayName("second letter from the same sender is suppressed too")
        fun `second letter from the same sender is suppressed too`() {
            // The bug this whole fix is for: a first Jobcenter letter (doc-1) makes the entity
            // path create a profile with sourceDocumentId = "doc-1". A second letter from the
            // SAME Jobcenter (doc-2) finds that profile already exists, so EntityLinkingUseCase
            // takes Action.Link — it links the existing profile to doc-2 without touching the
            // profile row, so sourceDocumentId is still "doc-1". Filtering coverage on
            // sourceDocumentId == documentId would then ask "is there a profile sourced from
            // doc-2?", find none, and let the Jobcenter field suggestion reappear on doc-2 —
            // reopening a question about a sender the user already has a linked profile for.
            // Since profiles exist specifically to recognise repeat senders, this was the
            // common case failing, not an edge case.
            val fieldSuggestion = suggestion(extractedOrganization = "Jobcenter Berlin")
            val linkedToDoc1ThenAlsoToDoc2 = testProfile(name = "Jobcenter Berlin")
                .copy(sourceDocumentId = "doc-1", sourceEntityName = "jobcenter berlin")

            val result = EntityCoverageFilter.apply(
                listOf(fieldSuggestion), emptyList(), listOf(linkedToDoc1ThenAlsoToDoc2),
            )

            assertThat(result).isEmpty()
        }

        @Test
        @DisplayName("a field- or user-created profile suppresses its own suggestion once linked")
        fun `a field-created profile suppresses its own field suggestion once linked here`() {
            // Previously this asserted the field suggestion survived, on the theory that the
            // very profile ProfileMatchingService just created and linked must not suppress the
            // suggestion that created it. That reasoning does not hold up: the question a
            // ProfileSuggestion asks is "shall I create or link a profile for this person?",
            // and once one is linked to this document — however it got here — the question is
            // already answered. Re-asking it is noise, not a safety net; presence of a linked
            // profile is what matters, not which subsystem created the row.
            val fieldSuggestion = suggestion(extractedOrganization = "Jobcenter Berlin")
            val fieldCreated = testProfile(name = "Jobcenter Berlin")

            val result = EntityCoverageFilter.apply(
                listOf(fieldSuggestion), emptyList(), listOf(fieldCreated),
            )

            assertThat(result).isEmpty()
        }
    }

    @Nested
    @DisplayName("No suppression beyond what the entity path actually found")
    inner class NoOverReach {

        @Test
        @DisplayName("a field suggestion the entity path knows nothing about is still shown")
        fun `still shown when the entity path found no sender at all`() {
            // The most important case: on a real device run, Qwen3.5 2B read a German letter
            // and identified NO sender entity whatsoever, while the field extractor still read
            // "Sender Organization" from the layout. Hiding the field suggestion whenever the
            // AI ran at all — rather than only when it named this specific person — would leave
            // the document with no profile prompt whatsoever, strictly worse than a duplicate.
            val fieldSuggestion = suggestion(extractedOrganization = "Jobcenter Mitte")
            // The AI did find something, just not the sender — proof this isn't "empty input".
            val unrelatedProposal = proposal(
                entityName = "Layla Mustermann", role = ProfileRole.RELATED,
            )

            val result = EntityCoverageFilter.apply(
                listOf(fieldSuggestion), listOf(unrelatedProposal), emptyList(),
            )

            assertThat(result).containsExactly(fieldSuggestion)
        }

        @Test
        @DisplayName("with no entity data at all, every field suggestion is shown, as before")
        fun `is a no-op with no model installed`() {
            // No chat model installed: extraction falls back to the regex extractor, which
            // never produces a RecognisedEntity, so there is no EntityProposal and no
            // entity-linked profile. Behaviour must be byte-for-byte what it was before this
            // filter existed — verified here by identity, not just equality.
            val suggestions = listOf(
                suggestion(
                    role = ProfileRole.SENDER, extractedOrganization = "Jobcenter Berlin",
                ),
                suggestion(role = ProfileRole.RECEIVER, extractedName = "Aylin Mustermann"),
            )

            val result =
                EntityCoverageFilter.apply(suggestions, emptyList(), emptyList())

            assertThat(result).isSameInstanceAs(suggestions)
        }
    }

    @Nested
    @DisplayName("Name comparison")
    inner class NameComparison {

        @Test
        @DisplayName("name matching survives case and whitespace differences")
        fun `case and surrounding whitespace do not defeat the match`() {
            val fieldSuggestion = suggestion(extractedOrganization = "  Jobcenter Berlin  ")
            val entityProposal = proposal(entityName = "JOBCENTER berlin")

            val result = EntityCoverageFilter.apply(
                listOf(fieldSuggestion), listOf(entityProposal), emptyList(),
            )

            assertThat(result).isEmpty()
        }

        @Test
        @DisplayName("an organisation entity covers a field suggestion that only carries a name")
        fun `either extracted field can match either side of an entity's identity`() {
            // A ProfileSuggestion carries extractedName and extractedOrganization separately;
            // the entity path may know the same party by either string (an AUTHORITY/COMPANY
            // entity is proposed by its organisation name, a PERSON by their own name). Either
            // side matching counts as the same party — checked here via extractedName matching
            // a proposal's entityName (the organisation-as-entityName case), and below via
            // extractedOrganization matching a proposal's organization field.
            val nameOnlySuggestion = suggestion(
                extractedName = "Jobcenter Berlin", extractedOrganization = null,
            )
            val entityProposal = proposal(entityName = "Jobcenter Berlin")

            assertThat(
                EntityCoverageFilter.apply(
                    listOf(nameOnlySuggestion), listOf(entityProposal), emptyList(),
                ),
            ).isEmpty()
        }

        @Test
        @DisplayName("a suggestion's organisation matches a proposal's organisation field")
        fun `extractedOrganization matches a proposal's organization, not just its entityName`() {
            // A SENDER_CONTACT proposal names a *person* (entityName = "Frau Müller") but
            // carries the sender's organisation separately (organization = "Jobcenter
            // Berlin") — see EntityProposal's doc. A field suggestion that only extracted the
            // organisation must still be recognised as the same party.
            val orgOnlySuggestion = suggestion(extractedOrganization = "Jobcenter Berlin")
            val contactProposal = proposal(
                entityName = "Frau Müller",
                organization = "Jobcenter Berlin",
                role = ProfileRole.CASE_WORKER,
            )

            assertThat(
                EntityCoverageFilter.apply(
                    listOf(orgOnlySuggestion), listOf(contactProposal), emptyList(),
                ),
            ).isEmpty()
        }
    }
}
