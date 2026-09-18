package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.MatchType
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [ProfileMatcher] — the scoring that decides whether a scanned letter is
 * auto-linked to an existing correspondent, offered for confirmation, or treated as
 * a new contact.
 *
 * These thresholds act directly on user data: an EXACT_MATCH suggests auto-linking,
 * so a false positive files a letter under the wrong organisation.
 *
 * Tests marked **BUG** assert current behaviour and name the defect.
 */
class ProfileMatcherTest {

    private val repo = FakeProfileRepository()
    private val matcher = ProfileMatcher(repo)

    private fun field(name: String, value: String) = ExtractedData(
        id = "f-$name",
        documentId = "doc-1",
        fieldName = name,
        fieldValue = value,
        fieldType = ExtractedFieldType.TEXT,
        confidence = 1f,
    )

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Match classification")
    inner class Classification {

        @Test
        fun `identical organisation yields EXACT_MATCH`() = runTest {
            repo.similarProfilesOverride = listOf(
                testProfile(name = "Jobcenter Berlin", organization = "Jobcenter Berlin"),
            )

            val suggestions = matcher.matchProfiles(
                "doc-1",
                listOf(field("Sender Organization", "Jobcenter Berlin")),
            )

            assertThat(suggestions).hasSize(1)
            assertThat(suggestions[0].matchType).isEqualTo(MatchType.EXACT_MATCH)
            assertThat(suggestions[0].confidence).isAtLeast(0.95f)
            assertThat(suggestions[0].role).isEqualTo(ProfileRole.SENDER)
        }

        @Test
        fun `no candidates yields NEW_PROFILE with zero confidence`() = runTest {
            repo.similarProfilesOverride = emptyList()

            val suggestions = matcher.matchProfiles(
                "doc-1",
                listOf(field("Sender Organization", "Unbekannte GmbH")),
            )

            assertThat(suggestions[0].matchType).isEqualTo(MatchType.NEW_PROFILE)
            assertThat(suggestions[0].confidence).isEqualTo(0f)
            assertThat(suggestions[0].existingProfile).isNull()
        }

        @Test
        fun `partial name overlap yields POSSIBLE_MATCH`() = runTest {
            repo.similarProfilesOverride = listOf(
                testProfile(name = "Max Mustermann", organization = null),
            )

            val suggestions = matcher.matchProfiles(
                "doc-1",
                listOf(field("Sender Name", "Max")),
            )

            assertThat(suggestions[0].matchType).isEqualTo(MatchType.POSSIBLE_MATCH)
            assertThat(suggestions[0].confidence).isLessThan(0.95f)
        }

        @Test
        fun `sender and receiver both produce suggestions`() = runTest {
            repo.similarProfilesOverride = emptyList()

            val suggestions = matcher.matchProfiles(
                "doc-1",
                listOf(
                    field("Sender Organization", "Jobcenter"),
                    field("Receiver Name", "Max Mustermann"),
                ),
            )

            assertThat(suggestions.map { it.role })
                .containsExactly(ProfileRole.SENDER, ProfileRole.RECEIVER)
        }

        @Test
        fun `no sender or receiver fields yields no suggestions`() = runTest {
            val suggestions = matcher.matchProfiles("doc-1", listOf(field("Subject", "Hallo")))
            assertThat(suggestions).isEmpty()
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scoring defects")
    inner class ScoringDefects {

        @Test
        @DisplayName("FIXED: a zero-confidence candidate is not offered")
        fun `completely unrelated candidate falls through to NEW_PROFILE`() = runTest {
            repo.similarProfilesOverride = listOf(
                testProfile(name = "Allianz Versicherung", organization = "Allianz"),
            )

            val suggestions = matcher.matchProfiles(
                "doc-1",
                listOf(field("Sender Organization", "Stadtwerke München")),
            )

            assertThat(suggestions[0].matchType).isEqualTo(MatchType.NEW_PROFILE)
            assertThat(suggestions[0].existingProfile).isNull()
        }

        @Test
        @DisplayName("FIXED: the best-scoring candidate wins regardless of order")
        fun `perfect match later in the list is chosen over a weak first entry`() = runTest {
            val weak = testProfile(id = "weak", name = "Jobcenter Hamburg", organization = "Jobcenter Hamburg")
            val perfect = testProfile(id = "perfect", name = "Jobcenter Berlin", organization = "Jobcenter Berlin")
            repo.similarProfilesOverride = listOf(weak, perfect)

            val suggestions = matcher.matchProfiles(
                "doc-1",
                listOf(field("Sender Organization", "Jobcenter Berlin")),
            )

            assertThat(suggestions[0].existingProfile?.id).isEqualTo("perfect")
            assertThat(suggestions[0].matchType).isEqualTo(MatchType.EXACT_MATCH)
        }

        @Test
        @DisplayName("FIXED: short organisation substrings no longer match")
        fun `two-letter legal suffix does not produce a false positive`() = runTest {
            // Substring matching now requires >= MIN_SUBSTRING_MATCH_LENGTH characters,
            // so the legal form "AG" scores 0 instead of 0.8.
            repo.similarProfilesOverride = listOf(
                testProfile(name = "Allianz AG", organization = "Allianz AG"),
            )

            val suggestions = matcher.matchProfiles(
                "doc-1",
                listOf(field("Sender Organization", "AG")),
            )

            assertThat(suggestions[0].matchType).isEqualTo(MatchType.NEW_PROFILE)
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Linking side effects")
    inner class Linking {

        @Test
        fun `creating a profile also links it to the document`() = runTest {
            repo.similarProfilesOverride = emptyList()
            val suggestion = matcher.matchProfiles(
                "doc-42",
                listOf(field("Sender Organization", "Neue GmbH")),
            ).first()

            val result = matcher.createAndLinkProfile(suggestion)

            assertThat(result).isInstanceOf(com.postsaimanager.core.common.result.PamResult.Success::class.java)
            assertThat(repo.links).hasSize(1)
            assertThat(repo.links[0].second).isEqualTo("doc-42")
            assertThat(repo.links[0].third).isEqualTo(ProfileRole.SENDER)
        }

        @Test
        fun `linking an existing profile backfills missing contact details`() = runTest {
            val existing = testProfile(id = "p9", name = "Jobcenter", organization = "Jobcenter", email = null)
            repo.seed(existing)
            repo.similarProfilesOverride = listOf(existing)

            val suggestion = matcher.matchProfiles(
                "doc-7",
                listOf(
                    field("Sender Organization", "Jobcenter"),
                    field("Sender Email", "kontakt@jobcenter.de"),
                ),
            ).first()

            matcher.linkExistingProfile(suggestion)

            assertThat(repo.updated).hasSize(1)
            assertThat(repo.updated[0].email).isEqualTo("kontakt@jobcenter.de")
            assertThat(repo.links.map { it.first }).contains("p9")
        }

        @Test
        fun `existing contact details are never overwritten by extracted values`() = runTest {
            val existing = testProfile(
                id = "p9", name = "Jobcenter", organization = "Jobcenter",
                email = "original@jobcenter.de",
            )
            repo.seed(existing)
            repo.similarProfilesOverride = listOf(existing)

            val suggestion = matcher.matchProfiles(
                "doc-7",
                listOf(
                    field("Sender Organization", "Jobcenter"),
                    field("Sender Email", "neu@jobcenter.de"),
                ),
            ).first()

            matcher.linkExistingProfile(suggestion)

            // Nothing to backfill (email already present) → no write at all,
            // so the stored value cannot have been clobbered.
            assertThat(repo.updated).isEmpty()
        }

        @Test
        fun `backfills a missing field while preserving one that is already set`() = runTest {
            val existing = testProfile(
                id = "p9", name = "Jobcenter", organization = "Jobcenter",
                email = "original@jobcenter.de", phone = null,
            )
            repo.seed(existing)
            repo.similarProfilesOverride = listOf(existing)

            val suggestion = matcher.matchProfiles(
                "doc-7",
                listOf(
                    field("Sender Organization", "Jobcenter"),
                    field("Sender Email", "neu@jobcenter.de"),
                    field("Sender Phone", "030-555"),
                ),
            ).first()

            matcher.linkExistingProfile(suggestion)

            val written = repo.updated.single()
            assertThat(written.phone).isEqualTo("030-555")                  // filled
            assertThat(written.email).isEqualTo("original@jobcenter.de")    // preserved
        }

        @Test
        @DisplayName("FIXED: no database write when nothing changed")
        fun `linking issues no update when there is nothing to backfill`() = runTest {
            // modifiedAt is no longer part of the comparison copy(), so the guard is live.
            val existing = testProfile(
                id = "p9", name = "Jobcenter", organization = "Jobcenter",
                email = "original@jobcenter.de", phone = "030-1", street = "Allee 1",
            )
            repo.seed(existing)
            repo.similarProfilesOverride = listOf(existing)

            // Extraction supplies nothing new at all.
            val suggestion = matcher.matchProfiles(
                "doc-7",
                listOf(field("Sender Organization", "Jobcenter")),
            ).first()

            matcher.linkExistingProfile(suggestion)

            assertThat(repo.updated).isEmpty()
            assertThat(repo.links.map { it.first }).contains("p9")
        }

        @Test
        fun `linking with no existing profile returns an error`() = runTest {
            repo.similarProfilesOverride = emptyList()
            val suggestion = matcher.matchProfiles(
                "doc-1",
                listOf(field("Sender Organization", "Neue GmbH")),
            ).first()

            val result = matcher.linkExistingProfile(suggestion)

            assertThat(result).isInstanceOf(com.postsaimanager.core.common.result.PamResult.Error::class.java)
            assertThat(repo.links).isEmpty()
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Failure handling")
    inner class Failures {

        @Test
        fun `repository failure degrades to NEW_PROFILE rather than throwing`() = runTest {
            repo.failWith = com.postsaimanager.core.common.result.PamError
                .DatabaseError(IllegalStateException("boom"))

            val suggestions = matcher.matchProfiles(
                "doc-1",
                listOf(field("Sender Organization", "Jobcenter")),
            )

            assertThat(suggestions[0].matchType).isEqualTo(MatchType.NEW_PROFILE)
        }
    }
}
