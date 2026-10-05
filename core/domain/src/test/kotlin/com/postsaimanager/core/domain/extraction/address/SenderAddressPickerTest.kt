package com.postsaimanager.core.domain.extraction.address

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.AddressPartValue
import com.postsaimanager.core.model.PostalAddress
import org.junit.jupiter.api.Test

class SenderAddressPickerTest {

    private fun address(tag: String, verified: Boolean, postcode: Boolean = true) = PostalAddress(
        lines = listOf(tag),
        postcode = if (postcode) AddressPartValue("12345", 0) else null,
        verified = verified,
    )

    private fun candidate(source: SenderSource, verified: Boolean, postcode: Boolean = true) =
        SenderCandidate(source, address(source.name, verified, postcode))

    @Test
    fun `the letterhead beats the return line, which beats the footer`() {
        val pick = SenderAddressPicker().pick(
            listOf(
                candidate(SenderSource.FOOTER, true), candidate(SenderSource.RETURN_ADDRESS_LINE, true), candidate(SenderSource.LETTERHEAD, true),
            ),
        )
        assertThat(pick.source).isEqualTo(SenderSource.LETTERHEAD)
        assertThat(pick.alternatives.map { it.source }).containsExactly(SenderSource.RETURN_ADDRESS_LINE, SenderSource.FOOTER).inOrder()
    }

    @Test
    fun `the first that verifies wins, even from a less preferred place`() {
        val pick = SenderAddressPicker().pick(
            listOf(candidate(SenderSource.LETTERHEAD, false), candidate(SenderSource.FOOTER, true)),
        )
        assertThat(pick.source).isEqualTo(SenderSource.FOOTER)
        assertThat(pick.alternatives.map { it.source }).containsExactly(SenderSource.LETTERHEAD)
    }

    @Test
    fun `with nothing verified the first with a postcode is taken, else the first`() {
        val picker = SenderAddressPicker()
        assertThat(picker.pick(listOf(candidate(SenderSource.LETTERHEAD, false, postcode = false), candidate(SenderSource.FOOTER, false))).source)
            .isEqualTo(SenderSource.FOOTER)
        assertThat(picker.pick(listOf(candidate(SenderSource.FOOTER, false, false), candidate(SenderSource.LETTERHEAD, false, false))).source)
            .isEqualTo(SenderSource.LETTERHEAD)
    }

    @Test
    fun `the preference order is data`() {
        val picker = SenderAddressPicker(order = listOf(SenderSource.FOOTER, SenderSource.LETTERHEAD, SenderSource.RETURN_ADDRESS_LINE))
        val pick = picker.pick(listOf(candidate(SenderSource.LETTERHEAD, true), candidate(SenderSource.FOOTER, true)))
        assertThat(pick.source).isEqualTo(SenderSource.FOOTER)
    }

    @Test
    fun `no candidate is no pick`() {
        val pick = SenderAddressPicker().pick(emptyList())
        assertThat(pick.address).isNull()
        assertThat(pick.alternatives).isEmpty()
    }
}
