package com.postsaimanager.core.domain.extraction.address

import com.postsaimanager.core.model.AddressPart
import com.postsaimanager.core.model.PostalAddress

/** Where a sender's address was printed. */
enum class SenderSource { LETTERHEAD, RETURN_ADDRESS_LINE, FOOTER }

/** A sender's address read from one place of the page. */
class SenderCandidate(val source: SenderSource, val address: PostalAddress)

/** The chosen sender address, where it came from, and the other candidates in the order of preference (the alternatives to offer). */
class SenderPick(val address: PostalAddress?, val source: SenderSource?, val alternatives: List<SenderCandidate>)

/**
 * Chooses the sender's address among the ones a letter prints (0 scores, pure). The order of preference is data ([order]): the
 * letterhead names the sender, the small return-address line above the window repeats it, and the footer is often the registered
 * office, so it is kept as an alternative. The first candidate that verifies wins; with none that verifies, the first that at least
 * holds a postcode, else the first. The rest are offered as alternatives, in the same order.
 */
class SenderAddressPicker(
    private val order: List<SenderSource> = DEFAULT_ORDER,
) {

    fun pick(candidates: List<SenderCandidate>): SenderPick {
        if (candidates.isEmpty()) return SenderPick(null, null, emptyList())
        val ranked = candidates.sortedBy { rank(it.source) }
        val chosen = ranked.firstOrNull { it.address.verified }
            ?: ranked.firstOrNull { it.address.part(AddressPart.POSTCODE) != null }
            ?: ranked.first()
        return SenderPick(chosen.address, chosen.source, ranked.filter { it !== chosen })
    }

    private fun rank(source: SenderSource): Int = order.indexOf(source).let { if (it < 0) order.size else it }

    companion object {
        val DEFAULT_ORDER: List<SenderSource> = listOf(SenderSource.LETTERHEAD, SenderSource.RETURN_ADDRESS_LINE, SenderSource.FOOTER)
    }
}
