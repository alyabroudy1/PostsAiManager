package com.postsaimanager.core.domain.extraction.address

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.Parties
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.PartyRelation
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.model.AddressPart
import com.postsaimanager.core.model.PostalAddress
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AddressLineLabelerTest {

    private val labeler = AddressLineLabeler()
    private val verifier = AddressVerifier()

    private fun read(
        lines: List<AddressLine>,
        session: FakePromptSession? = null,
        parties: Parties = Parties(),
        budget: Int = 16,
    ): Pair<PostalAddress, LabeledAddress> {
        val labeled = runBlocking { labeler.label(lines, parties, session?.let { AddressLineLabeler.Scoring(it, budget) }) }
        return verifier.verify(labeled, parties.allAddressees.map { it.name }) to labeled
    }

    private fun values(a: PostalAddress) = a.parts.associate { it.first to it.second.value }

    @Test
    fun `a german block is labelled by shape and by score, with the country from the postcode shape`() {
        val lines = listOf(
            line("Max Mustermann", 0.10f), line("Mustermann GmbH", 0.12f), line("Abteilung Einkauf", 0.14f),
            line("Musterstraße 12", 0.16f), line("54321 Beispieldorf", 0.18f),
        )
        val session = sessionSaying(
            "Max Mustermann" to LineAsk.PERSON, "Mustermann GmbH" to LineAsk.ORGANISATION, "Abteilung Einkauf" to LineAsk.DEPARTMENT,
        )
        val (address, labeled) = read(lines, session)
        assertThat(address.recipientNames.map { it.value }).containsExactly("Max Mustermann")
        assertThat(address.organisation?.value).isEqualTo("Mustermann GmbH")
        assertThat(address.department?.value).isEqualTo("Abteilung Einkauf")
        assertThat(address.street?.value).isEqualTo("Musterstraße")
        assertThat(address.houseNumber?.value).isEqualTo("12")
        assertThat(address.postcode?.value).isEqualTo("54321")
        assertThat(address.city?.value).isEqualTo("Beispieldorf")
        assertThat(address.countryIso2?.value).isEqualTo("DE")
        assertThat(address.formatId).isEqualTo("DE")
        assertThat(address.verified).isTrue()
        assertThat(address.lines).hasSize(5)
        assertThat(labeled.countrySource).isEqualTo(CountrySource.POSTCODE_SHAPE)
        // The budget: 3 word-only lines under 4 labels plus one street-shaped line under 2 delivery points.
        assertThat(session.cells).isEqualTo(14)
        assertThat(labeled.cells).isEqualTo(14)
    }

    @Test
    fun `the labels are scored, never generated`() {
        val session = sessionSaying("Erika Mustermann" to LineAsk.PERSON)
        read(listOf(line("Erika Mustermann", 0.10f), line("Musterstraße 12", 0.12f), line("54321 Beispieldorf", 0.14f)), session)
        assertThat(session.asks).isEmpty()
        assertThat(session.grids).isEqualTo(2)
    }

    @Test
    fun `a line no label wins stays a raw line with a low confidence`() {
        val session = sessionSaying()
        val (address, _) = read(listOf(line("Gebäude B", 0.10f), line("Musterstraße 12", 0.12f), line("54321 Beispieldorf", 0.14f)), session)
        assertThat(address.recipientNames).isEmpty()
        assertThat(address.addressExtra?.value).isEqualTo("Gebäude B")
        assertThat(address.addressExtra!!.confidence).isAtMost(ConfidenceCombiner.LOW)
    }

    @Test
    fun `a care-of line is a routing label, not a name`() {
        val session = sessionSaying("Jonas Mustermann" to LineAsk.PERSON, "c/o Familie Beispiel" to LineAsk.ROUTING)
        val (address, _) = read(
            listOf(line("Jonas Mustermann", 0.10f), line("c/o Familie Beispiel", 0.12f), line("Beispielgasse 3", 0.14f), line("12345 Beispielstadt", 0.16f)),
            session,
        )
        assertThat(address.recipientNames.map { it.value }).containsExactly("Jonas Mustermann")
        assertThat(address.careOf?.value).isEqualTo("c/o Familie Beispiel")
        assertThat(address.verified).isTrue()
    }

    @Test
    fun `a post office box has no house number and satisfies the required street`() {
        val lines = listOf(line("Max Mustermann", 0.10f), line("Postfach 10 11 22", 0.12f), line("12345 Berlin", 0.14f))
        val session = sessionSaying("Max Mustermann" to LineAsk.PERSON, "Postfach 10 11 22" to LineAsk.PO_BOX)
        val (address, _) = read(lines, session)
        assertThat(address.poBox?.value).isEqualTo("Postfach 10 11 22")
        assertThat(address.street).isNull()
        assertThat(address.houseNumber).isNull()
        assertThat(address.verified).isTrue()
    }

    @Test
    fun `a packstation line is a locker, also without a house number`() {
        val lines = listOf(line("Erika Mustermann", 0.10f), line("Packstation 123", 0.12f), line("54321 Beispieldorf", 0.14f))
        val session = sessionSaying("Erika Mustermann" to LineAsk.PERSON, "Packstation 123" to LineAsk.PACKSTATION)
        val (address, _) = read(lines, session)
        assertThat(address.packstation?.value).isEqualTo("Packstation 123")
        assertThat(address.street).isNull()
        assertThat(address.houseNumber).isNull()
        assertThat(address.verified).isTrue()
    }

    @Test
    fun `a box number in groups is never split into a street and a house number`() {
        val (address, _) = read(listOf(line("Max Mustermann", 0.10f), line("Postfach 10 11 22", 0.12f), line("12345 Berlin", 0.14f)))
        assertThat(address.street?.value).isEqualTo("Postfach 10 11 22")
        assertThat(address.houseNumber).isNull()
    }

    @Test
    fun `a uk block has the number first and the place and the postcode on their own lines`() {
        val lines = listOf(
            line("Mr John Smith", 0.10f), line("10 Downing Street", 0.12f), line("London", 0.14f), line("SW1A 2AA", 0.16f), line("United Kingdom", 0.18f),
        )
        val (address, labeled) = read(lines, sessionSaying("Mr John Smith" to LineAsk.PERSON))
        assertThat(address.houseNumber?.value).isEqualTo("10")
        assertThat(address.street?.value).isEqualTo("Downing Street")
        assertThat(address.city?.value).isEqualTo("London")
        assertThat(address.postcode?.value).isEqualTo("SW1A 2AA")
        assertThat(address.countryIso2?.value).isEqualTo("GB")
        assertThat(labeled.countrySource).isEqualTo(CountrySource.LINE)
        assertThat(address.verified).isTrue()
    }

    @Test
    fun `a us block has the region with the place, and the number first`() {
        val lines = listOf(line("John Smith", 0.10f), line("123 Main Street", 0.12f), line("Springfield, IL 62704", 0.14f))
        val (address, _) = read(lines, sessionSaying("John Smith" to LineAsk.PERSON))
        assertThat(address.houseNumber?.value).isEqualTo("123")
        assertThat(address.street?.value).isEqualTo("Main Street")
        assertThat(address.city?.value).isEqualTo("Springfield")
        assertThat(address.region?.value).isEqualTo("IL")
        assertThat(address.postcode?.value).isEqualTo("62704")
        assertThat(address.countryIso2?.value).isEqualTo("US")
        assertThat(address.verified).isTrue()
    }

    @Test
    fun `an emirates block has a region line and no postcode`() {
        val lines = listOf(
            line("السيد خالد", 0.10f, 0.6f, 0.9f), line("10 شارع الشيخ زايد", 0.12f, 0.6f, 0.9f), line("دبي", 0.14f, 0.6f, 0.9f),
            line("الإمارات العربية المتحدة", 0.16f, 0.6f, 0.9f),
        )
        val (address, _) = read(lines, sessionSaying("السيد خالد" to LineAsk.PERSON))
        assertThat(address.countryIso2?.value).isEqualTo("AE")
        assertThat(address.region?.value).isEqualTo("دبي")
        assertThat(address.postcode).isNull()
        assertThat(address.street).isNotNull()
        assertThat(address.verified).isTrue()
    }

    @Test
    fun `a foreign country line no format names keeps its shape but nothing is verified and confidence is capped`() {
        val lines = listOf(line("Jean Dupont", 0.10f), line("10 Rue de la Paix", 0.12f), line("75002 Paris", 0.14f), line("Frankreich", 0.16f))
        val (address, labeled) = read(lines, sessionSaying("Jean Dupont" to LineAsk.PERSON))
        assertThat(labeled.notes).contains(AddressLineLabeler.NOTE_COUNTRY_LINE_UNKNOWN)
        // the postcode shape says nothing about the country when a country line names another
        assertThat(address.countryIso2).isNull()
        assertThat(address.formatId).isNull()
        assertThat(address.postcode?.value).isEqualTo("75002")
        assertThat(address.verified).isFalse()
        assertThat(address.parts.map { it.second.confidence }.max()).isAtMost(ConfidenceCombiner.MEDIUM)
        assertThat(address.lines).hasSize(4)
    }

    @Test
    fun `an arabic block reads its digits and keeps rtl fragments of one row in reading order`() {
        val zone = LetterZone.ADDRESS_FIELD
        val lines = AddressLines.joinRows(
            AddressLines.of(
                listOf(
                    layoutLine("السيد أحمد محمد", 0.10f, zone, 0.50f, 0.90f),
                    layoutLine("8228 طريق الملك فهد", 0.12f, zone, 0.50f, 0.90f),
                    // OCR read the place and the postcode as two blocks on one row, Arabic digits: the place is the rightmost, so it is read first
                    layoutLine("١٢٣٤٥", 0.14f, zone, 0.40f, 0.55f),
                    layoutLine("الرياض", 0.14f, zone, 0.60f, 0.90f),
                    layoutLine("المملكة العربية السعودية", 0.16f, zone, 0.50f, 0.90f),
                ),
            ),
        )
        val (address, labeled) = read(lines, sessionSaying("السيد أحمد محمد" to LineAsk.PERSON))
        assertThat(labeled.lines.map { it.text }).contains("الرياض 12345")
        assertThat(address.countryIso2?.value).isEqualTo("SA")
        assertThat(address.postcode?.value).isEqualTo("12345")
        assertThat(address.city?.value).isEqualTo("الرياض")
        assertThat(address.houseNumber?.value).isEqualTo("8228")
        assertThat(address.verified).isTrue()
        assertThat(address.postcode!!.bbox!!.right).isEqualTo(0.90f)
    }

    @Test
    fun `lines the model already gave to a party need no score, and a household is noted`() {
        val parties = Parties(
            listOf(
                party("Familie Mustermann", relation = PartyRelation.HOUSEHOLD),
                party("Mustermann Consulting GmbH", PartyRole.CO_ADDRESSEE, PartyKind.COMPANY),
            ),
        )
        val lines = listOf(
            line("Mustermann Consulting GmbH", 0.10f), line("Familie Mustermann", 0.12f), line("Musterstraße 12", 0.14f), line("54321 Beispieldorf", 0.16f),
        )
        val session = sessionSaying()
        val (address, labeled) = read(lines, session, parties)
        assertThat(address.organisation?.value).isEqualTo("Mustermann Consulting GmbH")
        assertThat(address.recipientNames.map { it.value }).containsExactly("Familie Mustermann")
        assertThat(address.notes).contains(AddressLineLabeler.NOTE_HOUSEHOLD)
        // only the street-shaped line was scored
        assertThat(labeled.cells).isEqualTo(2)
        assertThat(address.recipientNames.first().confidence).isAtLeast(ConfidenceCombiner.HIGH)
    }

    @Test
    fun `a line holding several people is one line of several names`() {
        val parties = Parties(listOf(party("Max Mustermann"), party("Erika Mustermann", PartyRole.CO_ADDRESSEE)))
        val lines = listOf(line("Max Mustermann und Erika Mustermann", 0.10f), line("Musterstraße 12", 0.12f), line("54321 Beispieldorf", 0.14f))
        val (address, _) = read(lines, sessionSaying(), parties)
        assertThat(address.recipientNames.map { it.value }).containsExactly("Max Mustermann", "Erika Mustermann").inOrder()
        assertThat(address.recipientNames.map { it.lineIdx }.toSet()).containsExactly(0)
        assertThat(address.notes).contains(AddressLineLabeler.NOTE_MULTI_PERSON)
    }

    @Test
    fun `the budget caps the cells and what does not fit stays raw`() {
        val names = listOf("Alpha Beta", "Gamma Delta", "Epsilon Zeta", "Eta Theta", "Iota Kappa", "Lambda Mu")
        val lines = names.mapIndexed { i, n -> line(n, 0.10f + 0.02f * i) } +
            listOf(line("Musterstraße 12", 0.24f), line("54321 Beispieldorf", 0.26f))
        val session = sessionSaying()
        val (address, labeled) = read(lines, session, budget = 16)
        assertThat(session.cells).isAtMost(16)
        assertThat(labeled.notes).contains(AddressLineLabeler.NOTE_BUDGET)
        assertThat(address.addressExtra).isNotNull()
    }

    @Test
    fun `a failed scoring leaves the raw lines and notes it`() {
        val session = FakePromptSession()
        val (address, labeled) = read(listOf(line("Erika Mustermann", 0.10f), line("Musterstraße 12", 0.12f), line("54321 Beispieldorf", 0.14f)), session)
        assertThat(labeled.notes).contains(AddressLineLabeler.NOTE_SCORING_FAILED)
        assertThat(address.recipientNames).isEmpty()
        assertThat(address.addressExtra?.value).isEqualTo("Erika Mustermann")
        assertThat(address.postcode?.value).isEqualTo("54321")
    }

    @Test
    fun `without a session it is the shape pass alone and costs nothing`() {
        val labeled = labeler.shape(listOf(line("Erika Mustermann", 0.10f), line("Musterstraße 12", 0.12f), line("54321 Beispieldorf", 0.14f)))
        assertThat(labeled.cells).isEqualTo(0)
        assertThat(labeled.parts.map { it.part }).containsAtLeast(AddressPart.STREET, AddressPart.POSTCODE, AddressPart.CITY)
    }

    @Test
    fun `a zone with no postcode line yields raw lines with a capped confidence`() {
        val (address, labeled) = read(listOf(line("Bio Eier 10er", 0.10f), line("Bananen", 0.12f), line("SUMME EUR", 0.14f)))
        assertThat(labeled.hasPostcodeLine).isFalse()
        assertThat(address.verified).isFalse()
        assertThat(address.lines).hasSize(3)
        assertThat(address.parts.all { it.second.confidence <= ConfidenceCombiner.MEDIUM }).isTrue()
        assertThat(values(address)[AddressPart.ADDRESS_EXTRA]).contains("Bananen")
    }
}
