package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.result.getOrNull
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scores the existing profiles against a name and organisation: the one similarity rule the entity linker
 * ([EntityProfileLinker]) uses to decide whether a recognised organisation or person is already on file.
 */
@Singleton
class ProfileMatcher @Inject constructor(
    private val profileRepository: ProfileRepository,
) {

    /**
     * The best-scoring existing profile for a name/organization pair, or null with 0f when
     * nothing in the database resembles it at all.
     */
    suspend fun findBestMatch(
        name: String?,
        organization: String?,
        email: String? = null,
        /**
         * Restricts which [ProfileKind]s are eligible, applied *before* scoring rather than
         * to the winner after. `findSimilarProfiles` matches on the `organization` column
         * alone with no notion of type, so an organisation's own profile and a caseworker's
         * profile at that organisation can tie on an organisation-only search — filtering
         * only the winner would risk keeping the wrong one of the two on that tie; filtering
         * the pool first means the runner-up is still found instead of nothing at all.
         */
        profileKind: ((ProfileKind) -> Boolean)? = null,
    ): Pair<Profile?, Float> {
        val searchName = organization ?: name ?: return null to 0f
        val similar = profileRepository.findSimilarProfiles(searchName, organization).getOrNull() ?: emptyList()
        val candidates = if (profileKind != null) similar.filter { profileKind(it.kind) } else similar

        // Score EVERY candidate and take the highest. Taking `similar.first()` before any
        // scoring meant the "best match" was whatever order Room happened to return — a
        // perfect match further down the list was silently ignored.
        // A tie goes to the oldest profile (maxBy keeps the first of equals), not to Room's order, so two readings
        // of one name keep finding the same profile.
        val best = candidates
            .sortedWith(compareBy<Profile> { it.createdAt }.thenBy { it.id })
            .map { it to calculateMatchConfidence(it, name, organization, email) }
            .maxByOrNull { it.second }

        return best?.first to (best?.second ?: 0f)
    }

    private fun calculateMatchConfidence(
        profile: Profile,
        name: String?,
        organization: String?,
        email: String?,
    ): Float {
        var score = 0f
        var checks = 0

        // Organization exact match is strongest signal
        if (organization != null && profile.organization != null) {
            checks++
            val a = organization.lowercase().trim()
            val b = profile.organization!!.lowercase().trim()
            score += when {
                a == b -> 1.0f
                substringOverlap(a, b) -> 0.8f
                else -> 0f
            }
        }

        // Name match
        if (name != null) {
            checks++
            val a = name.lowercase().trim()
            val b = profile.name.lowercase().trim()
            score += when {
                a == b -> 1.0f
                substringOverlap(a, b) -> 0.7f
                else -> 0f
            }
        }

        // Email exact match
        if (email != null && profile.email != null) {
            checks++
            score += if (email.lowercase() == profile.email!!.lowercase()) 1.0f else 0f
        }

        return if (checks > 0) score / checks else 0f
    }

    /**
     * Substring containment, guarded against matches that carry no identifying signal.
     *
     * A bare legal form — "AG", "GmbH", "e.V." — appears in a large share of German
     * organisation names, so matching on it alone scored 0.8 against most of the
     * database. A plain length floor was the first attempt and was too blunt: it also
     * rejected legitimately short personal names such as "Max". Excluding the legal
     * forms explicitly is the targeted fix; the length floor stays only as a backstop
     * against one- and two-character fragments.
     */
    private fun substringOverlap(a: String, b: String): Boolean {
        val shorter = if (a.length <= b.length) a else b
        if (shorter.length < MIN_SUBSTRING_MATCH_LENGTH) return false
        if (shorter.trim().trimEnd('.') in NON_IDENTIFYING_TOKENS) return false
        return a.contains(b) || b.contains(a)
    }

    companion object {
        /** At or above this, the match is strong enough to link automatically. */
        const val EXACT_MATCH_CONFIDENCE = 0.95f

        /** Shortest string that may participate in substring matching. */
        const val MIN_SUBSTRING_MATCH_LENGTH = 3

        /** Legal forms and generic words that identify nobody on their own. */
        val NON_IDENTIFYING_TOKENS = setOf(
            "ag", "gmbh", "mbh", "kg", "ohg", "gbr", "ug", "se", "e.v", "ev",
            "ltd", "inc", "llc", "plc", "co", "corp", "sa", "nv", "bv",
            "gmbh & co", "und", "and", "der", "die", "das", "the",
        )
    }
}
