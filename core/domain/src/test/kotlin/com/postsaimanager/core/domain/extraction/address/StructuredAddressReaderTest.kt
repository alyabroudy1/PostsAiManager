package com.postsaimanager.core.domain.extraction.address

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.layout.PageLayoutView
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.zones.LayoutTemplates
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import com.postsaimanager.core.domain.extraction.zones.TemplateMatch
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class StructuredAddressReaderTest {

    private fun layout(vararg lines: LayoutLine) = LetterLayout(listOf(PageLayoutView(1, lines.toList())))

    private fun reader(profile: ScoringProfile = ScoringProfile()) = StructuredAddressReader(AddressLineLabeler(profile = profile), profile = profile)

    private fun match(second: Float) = TemplateMatch(
        LayoutTemplates.DIN5008_A, 0.80f,
        mapOf("DIN5008_A" to 0.80f, "DIN5008_B" to second, "INVOICE_TABLE" to 0.3f),
    )

    private fun read(layout: LetterLayout, match: TemplateMatch?, session: FakePromptSession?, profile: ScoringProfile = ScoringProfile()): AddressReading =
        runBlocking { reader(profile).read(layout, match = match, session = session) }

    /**
     * A letter whose address was not where the chosen template (form A, the address at the top) expects it: the analyzer zoned two
     * small-print lines as the address field, and the real address sits lower, where form B (the runner-up) puts it.
     */
    private val misZoned = layout(
        layoutLine("Musterfirma GmbH", 0.02f, LetterZone.LETTERHEAD),
        layoutLine("Beispielweg 7", 0.04f, LetterZone.LETTERHEAD),
        layoutLine("12345 Beispielstadt", 0.06f, LetterZone.LETTERHEAD),
        layoutLine("Kundenservice", 0.09f, LetterZone.ADDRESS_FIELD),
        layoutLine("Bereich Privatkunden", 0.11f, LetterZone.ADDRESS_FIELD),
        layoutLine("Musterfirma · Beispielweg 7 · 12345 Beispielstadt", 0.16f, LetterZone.RETURN_ADDRESS_LINE),
        layoutLine("Erika Mustermann", 0.18f, LetterZone.BODY),
        layoutLine("Musterstraße 12", 0.20f, LetterZone.BODY),
        layoutLine("54321 Beispieldorf", 0.22f, LetterZone.BODY),
    )

    @Test
    fun `the retry reads the runner-up template's address zone once when the first zone has no postcode line`() {
        val session = sessionSaying("Erika Mustermann" to LineAsk.PERSON)
        val reading = read(misZoned, match(second = 0.75f), session)
        assertThat(reading.retried).isTrue()
        val address = reading.addresses.getValue(PartyRole.ADDRESSEE)
        assertThat(address.lines).containsExactly("Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf").inOrder()
        assertThat(address.postcode?.value).isEqualTo("54321")
        assertThat(address.verified).isTrue()
        // the first zone was never scored: only the zone that shows a postcode line is labelled
        assertThat(session.scored.flatten().none { it.contains("Kundenservice") && it.contains("Is «Kundenservice»") }).isTrue()
    }

    @Test
    fun `no retry when the runner-up is not within the profile's margin`() {
        val reading = read(misZoned, match(second = 0.40f), sessionSaying())
        assertThat(reading.retried).isFalse()
        val address = reading.addresses.getValue(PartyRole.ADDRESSEE)
        assertThat(address.lines).containsExactly("Kundenservice", "Bereich Privatkunden").inOrder()
        assertThat(address.verified).isFalse()
        assertThat(address.parts.all { it.second.confidence <= 0.7f }).isTrue()
    }

    @Test
    fun `the margin is a profile value, not a literal`() {
        val wide = ScoringProfile(addressRetryMargin = 0.5f)
        assertThat(read(misZoned, match(second = 0.40f), sessionSaying(), wide).retried).isTrue()
        val narrow = ScoringProfile(addressRetryMargin = 0.01f)
        assertThat(read(misZoned, match(second = 0.75f), sessionSaying(), narrow).retried).isFalse()
    }

    @Test
    fun `no retry without a template match or when the first zone already has a postcode line`() {
        assertThat(read(misZoned, null, sessionSaying()).retried).isFalse()
        val fine = layout(
            layoutLine("Erika Mustermann", 0.10f, LetterZone.ADDRESS_FIELD),
            layoutLine("Musterstraße 12", 0.12f, LetterZone.ADDRESS_FIELD),
            layoutLine("54321 Beispieldorf", 0.14f, LetterZone.ADDRESS_FIELD),
        )
        assertThat(read(fine, match(second = 0.79f), sessionSaying()).retried).isFalse()
    }

    @Test
    fun `the sender is the letterhead's address, the return line and footer are alternatives, and its scores stay in budget`() {
        val letter = layout(
            layoutLine("Musterfirma GmbH", 0.02f, LetterZone.LETTERHEAD),
            layoutLine("Beispielweg 7", 0.04f, LetterZone.LETTERHEAD),
            layoutLine("12345 Beispielstadt", 0.06f, LetterZone.LETTERHEAD),
            layoutLine("Musterfirma · Beispielweg 7 · 12345 Beispielstadt", 0.16f, LetterZone.RETURN_ADDRESS_LINE),
            layoutLine("Erika Mustermann", 0.18f, LetterZone.ADDRESS_FIELD),
            layoutLine("Musterstraße 12", 0.20f, LetterZone.ADDRESS_FIELD),
            layoutLine("54321 Beispieldorf", 0.22f, LetterZone.ADDRESS_FIELD),
            layoutLine("Sitz der Gesellschaft: Register 5", 0.93f, LetterZone.FOOTER),
            layoutLine("Amtsweg 1", 0.95f, LetterZone.FOOTER),
            layoutLine("99999 Registerstadt", 0.97f, LetterZone.FOOTER),
        )
        val session = sessionSaying("Erika Mustermann" to LineAsk.PERSON, "Musterfirma GmbH" to LineAsk.ORGANISATION)
        val reading = read(letter, null, session)
        val sender = reading.addresses.getValue(PartyRole.SENDER)
        assertThat(sender.postcode?.value).isEqualTo("12345")
        assertThat(sender.organisation?.value).isEqualTo("Musterfirma GmbH")
        assertThat(sender.verified).isTrue()
        assertThat(reading.senderAlternatives.map { it.postcode?.value }).containsExactly("12345", "99999")
        assertThat(reading.recipientCells).isAtMost(AddressBudget().recipientCells)
        assertThat(reading.senderCells).isAtMost(AddressBudget().senderCells)
        assertThat(session.cells).isEqualTo(reading.recipientCells + reading.senderCells)
    }

    @Test
    fun `the return line is cut at its separators into an address`() {
        val letter = layout(
            layoutLine("Musterfirma · Beispielweg 7 · 12345 Beispielstadt", 0.16f, LetterZone.RETURN_ADDRESS_LINE),
            layoutLine("Erika Mustermann", 0.18f, LetterZone.ADDRESS_FIELD),
            layoutLine("Musterstraße 12", 0.20f, LetterZone.ADDRESS_FIELD),
            layoutLine("54321 Beispieldorf", 0.22f, LetterZone.ADDRESS_FIELD),
        )
        val sender = read(letter, null, sessionSaying()).addresses.getValue(PartyRole.SENDER)
        assertThat(sender.street?.value).isEqualTo("Beispielweg")
        assertThat(sender.houseNumber?.value).isEqualTo("7")
        assertThat(sender.city?.value).isEqualTo("Beispielstadt")
    }

    @Test
    fun `an rtl page with no address zone does not crash and reads no addressee`() {
        val letter = layout(
            layoutLine("مرحبا بكم في الرسالة", 0.30f, LetterZone.BODY, 0.50f, 0.90f),
            layoutLine("شكرا", 0.34f, LetterZone.BODY, 0.50f, 0.90f),
        )
        val reading = read(letter, match(second = 0.79f), sessionSaying())
        assertThat(reading.addresses).isEmpty()
    }

    @Test
    fun `without a session only the shape pass runs and nothing is scored`() {
        val reading = read(misZoned, match(second = 0.75f), null)
        assertThat(reading.recipientCells).isEqualTo(0)
        assertThat(reading.addresses.getValue(PartyRole.ADDRESSEE).postcode?.value).isEqualTo("54321")
    }
}
