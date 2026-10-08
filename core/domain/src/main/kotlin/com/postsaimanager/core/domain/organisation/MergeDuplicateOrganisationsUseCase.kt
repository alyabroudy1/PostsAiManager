package com.postsaimanager.core.domain.organisation

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.normaliseEntityName
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileKind
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Two organisation profiles that readings made for the same name are one organisation: the newer is folded into the older
 * ([ProfileRepository.mergeProfiles]: document links, contacts, events, suggestions, notes move; the newer row is deleted).
 *
 * Only profiles no person ever touched are merged: machine-created (a source document is recorded) and never edited (the modification
 * time is still the creation time). A profile the user edited, or made by hand, is never merged automatically, and neither are two
 * that disagree on a detail both carry (a different street, say): they may be two branches. What the kept profile lacks, it takes
 * from the merged one.
 */
class MergeDuplicateOrganisationsUseCase @Inject constructor(
    private val profiles: ProfileRepository,
) {

    /** Merges the duplicates named [entityKey] (a [normaliseEntityName] of the organisation's name); returns how many profiles were folded in. */
    suspend operator fun invoke(entityKey: String): Int {
        val untouched = profiles.getProfiles().first()
            .filter { isUntouchedOrganisation(it) && keyOf(it) == entityKey }
            .sortedWith(compareBy<Profile> { it.createdAt }.thenBy { it.id })
        if (untouched.size < 2) return 0

        var kept = untouched.first()
        var merged = 0
        for (other in untouched.drop(1)) {
            if (conflict(kept, other)) continue
            val result = profiles.mergeProfiles(kept.id, other.id)
            if (result !is PamResult.Success) continue
            merged++
            val enriched = fillGaps(kept, other)
            if (enriched != kept) {
                profiles.updateProfile(enriched)
                kept = enriched
            }
        }
        return merged
    }

    private fun isUntouchedOrganisation(p: Profile) =
        p.kind == ProfileKind.ORGANISATION && p.householdRole == null && p.sourceDocumentId != null && p.modifiedAt == p.createdAt

    private fun keyOf(p: Profile) = normaliseEntityName(p.sourceEntityName ?: p.organization ?: p.name)

    private fun details(p: Profile) = listOf(
        p.department, p.street, p.city, p.postalCode, p.country, p.phone, p.email, p.website, p.reference,
    )

    /** Both carry a value for the same detail and the values differ. */
    private fun conflict(a: Profile, b: Profile): Boolean =
        details(a).zip(details(b)).any { (x, y) ->
            !x.isNullOrBlank() && !y.isNullOrBlank() && normaliseEntityName(x) != normaliseEntityName(y)
        }

    private fun fillGaps(keep: Profile, other: Profile) = keep.copy(
        department = keep.department.orGap(other.department),
        street = keep.street.orGap(other.street),
        city = keep.city.orGap(other.city),
        postalCode = keep.postalCode.orGap(other.postalCode),
        country = keep.country.orGap(other.country),
        phone = keep.phone.orGap(other.phone),
        email = keep.email.orGap(other.email),
        website = keep.website.orGap(other.website),
        reference = keep.reference.orGap(other.reference),
    )

    private fun String?.orGap(other: String?): String? = if (isNullOrBlank()) other else this
}
