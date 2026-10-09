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
 * Only machine-created profiles (a source document is recorded) are merged; one made by hand never is. Of the duplicates, the one a
 * person edited (the modification time differs from the creation time) is the one kept, else the oldest; two edited ones are never
 * merged into each other. Two that disagree on a detail both carry (a different street, say) stay apart: they may be two branches.
 * What the kept profile lacks, it takes from the merged one.
 */
class MergeDuplicateOrganisationsUseCase @Inject constructor(
    private val profiles: ProfileRepository,
) {

    /** Merges the duplicates named [entityKey] (a [normaliseEntityName] of the organisation's name); returns how many profiles were folded in. */
    suspend operator fun invoke(entityKey: String): Int {
        val machineMade = profiles.getProfiles().first()
            .filter { isMachineOrganisation(it) && keyOf(it) == entityKey }
            .sortedWith(compareBy<Profile> { it.createdAt }.thenBy { it.id })
        val (edited, untouched) = machineMade.partition { it.modifiedAt != it.createdAt }
        // The one profile a person edited (own details, an address ...) is the one kept, so nothing they wrote is lost; two edited ones
        // are two decisions and stay apart (the untouched ones still fold into the oldest of the untouched).
        val keeper = if (edited.size == 1) edited.first() else untouched.firstOrNull()
        val others = untouched.filter { it.id != keeper?.id }
        if (keeper == null || others.isEmpty()) return 0

        var kept: Profile = keeper
        var merged = 0
        for (other in others) {
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

    private fun isMachineOrganisation(p: Profile) =
        p.kind == ProfileKind.ORGANISATION && p.householdRole == null && p.sourceDocumentId != null

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
