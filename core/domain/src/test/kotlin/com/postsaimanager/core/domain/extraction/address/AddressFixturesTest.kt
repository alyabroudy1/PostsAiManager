package com.postsaimanager.core.domain.extraction.address

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.benchmark.BenchmarkFixtures
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.Parties
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.zones.TemplateMatcher
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** The repo's own benchmark letters (real phone OCR of invented letters): the shape pass and a fake session's labels on every page-1 address zone. */
class AddressFixturesTest {

    private val docs = BenchmarkFixtures.load().docs

    /** A fake "model" that says every word-only line is a person and nothing else. */
    private fun session(): FakePromptSession = FakePromptSession().apply {
        scorer = { c -> if (c.contains(" ${LineAsk.PERSON.statement}? Answer:")) 5.0 else -5.0 }
        runBlocking { open("the letter") }
    }

    private fun read(doc: com.postsaimanager.core.domain.benchmark.ManifestDoc, fixture: com.postsaimanager.core.domain.benchmark.Fixture): Triple<AddressReading, FakePromptSession, Boolean> {
        val layout = LetterLayoutAnalyzer.analyze(fixture.pages.map { it.blocks })
        val session = session()
        val parties = Parties(doc.roles.addressees.map { party(it) })
        val reading = runBlocking {
            StructuredAddressReader(AddressLineLabeler()).read(layout, parties, TemplateMatcher().match(layout), session)
        }
        return Triple(reading, session, layout.zone(LetterZone.ADDRESS_FIELD).isNotEmpty())
    }

    @Test
    fun `the fixtures are there`() {
        assertThat(docs.size).isAtLeast(14)
    }

    @Test
    fun `every page-1 address zone with a postcode line verifies postcode, city and street`() {
        var checked = 0
        for ((doc, fixture) in docs) {
            val (reading, _, hasZone) = read(doc, fixture)
            if (!hasZone) continue
            val address = reading.addresses[PartyRole.ADDRESSEE] ?: error("${doc.key}: an address zone but no address")
            val pageText = fixture.pages.first().blocks.joinToString("\n") { it.text }
            if (address.postcode == null) {
                // not an address (the receipt's item list): raw lines, nothing verified, nothing trusted
                assertThat(address.verified).isFalse()
                assertThat(address.parts.all { it.second.confidence <= ConfidenceCombiner.MEDIUM }).isTrue()
                continue
            }
            checked++
            assertThat(address.verified).isTrue()
            assertThat(address.street).isNotNull()
            assertThat(address.city).isNotNull()
            assertThat(address.formatId).isNotNull()
            assertThat(pageText).contains(address.postcode!!.value)
            assertThat(pageText).contains(address.city!!.value)
            assertThat(address.lines).isNotEmpty()
            assertThat(address.postcode!!.bbox).isNotNull()
            assertThat(address.postcode!!.confidence).isAtLeast(ConfidenceCombiner.REVIEW_BELOW)
        }
        // the ten n-letters, the invoice, the tax letter, the degraded scan and the english letter
        assertThat(checked).isAtLeast(14)
    }

    @Test
    fun `the english letter reads as a uk address and the german ones as german`() {
        val byKey = docs.associate { it.first.key to it }
        val uk = read(byKey.getValue("english-ambiguous-3p").first, byKey.getValue("english-ambiguous-3p").second).first.addresses.getValue(PartyRole.ADDRESSEE)
        assertThat(uk.formatId).isEqualTo("GB")
        assertThat(uk.houseNumber?.value).isEqualTo("22")
        val de = read(byKey.getValue("N1-mahnung-telco-qr-1p").first, byKey.getValue("N1-mahnung-telco-qr-1p").second).first.addresses.getValue(PartyRole.ADDRESSEE)
        assertThat(de.formatId).isEqualTo("DE")
        assertThat(de.countryIso2?.value).isEqualTo("DE")
        assertThat(de.street?.value).isEqualTo("Musterstraße")
        assertThat(de.houseNumber?.value).isEqualTo("12")
    }

    @Test
    fun `the receipt's item list is raw lines with a capped confidence, not an address`() {
        val (doc, fixture) = docs.first { it.first.key == "receipt-noise-1p" }
        val address = read(doc, fixture).first.addresses.getValue(PartyRole.ADDRESSEE)
        assertThat(address.verified).isFalse()
        assertThat(address.postcode).isNull()
        assertThat(address.lines.size).isAtLeast(10)
        assertThat(address.parts.all { it.second.confidence <= ConfidenceCombiner.MEDIUM }).isTrue()
    }

    @Test
    fun `the arabic fixture has no address zone and reads without a crash`() {
        val (doc, fixture) = docs.first { it.first.key == "arabic-rtl-1p" }
        val (reading, _, hasZone) = read(doc, fixture)
        assertThat(hasZone).isFalse()
        assertThat(reading.addresses[PartyRole.ADDRESSEE]).isNull()
    }

    @Test
    fun `the score budget holds on every fixture, at most 16 for the recipient and 8 for the sender`() {
        for ((doc, fixture) in docs) {
            val (reading, session, _) = read(doc, fixture)
            assertThat(reading.recipientCells).isAtMost(16)
            assertThat(reading.senderCells).isAtMost(8)
            assertThat(session.cells).isEqualTo(reading.recipientCells + reading.senderCells)
        }
    }

    @Test
    fun `a sender address is read from the letterhead, return line or footer where one has a postcode`() {
        var senders = 0
        for ((doc, fixture) in docs) {
            val sender = read(doc, fixture).first.addresses[PartyRole.SENDER] ?: continue
            senders++
            assertThat(sender.lines).isNotEmpty()
            if (sender.verified) assertThat(sender.postcode).isNotNull()
        }
        assertThat(senders).isAtLeast(10)
    }
}
