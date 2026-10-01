package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.address.AddressLineLabeler
import com.postsaimanager.core.domain.extraction.address.AddressRows
import com.postsaimanager.core.domain.extraction.address.AddressVerifier
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.AddressPart
import com.postsaimanager.core.model.AddressPartValue
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.PostalAddress
import com.postsaimanager.core.model.TextBounds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class ExtractionV2AdapterAddressTest {

    private val adapter = ExtractionV2Adapter()

    private fun box(y: Float) = TextBounds(0.1f, y, 0.4f, y + 0.012f)

    private val addressee = PostalAddress(
        lines = listOf("Max Mustermann", "Erika Mustermann", "Postfach 10 11 22", "54321 Beispieldorf"),
        recipientNames = listOf(AddressPartValue("Max Mustermann", 0, box(0.10f), 0.9f), AddressPartValue("Erika Mustermann", 1, box(0.12f), 0.7f)),
        poBox = AddressPartValue("Postfach 10 11 22", 2, box(0.14f), 0.9f),
        postcode = AddressPartValue("54321", 3, box(0.16f), 0.9f),
        city = AddressPartValue("Beispieldorf", 3, box(0.16f), 0.9f),
        countryIso2 = AddressPartValue("DE", 3, box(0.16f), 0.9f),
        formatId = "DE",
        verified = true,
    )

    private val sender = PostalAddress(
        lines = listOf("Musterfirma GmbH", "Beispielweg 7", "12345 Beispielstadt"),
        organisation = AddressPartValue("Musterfirma GmbH", 0, box(0.02f), 0.9f),
        street = AddressPartValue("Beispielweg", 1, box(0.04f), 0.7f),
        houseNumber = AddressPartValue("7", 1, box(0.04f), 0.7f),
        postcode = AddressPartValue("12345", 2, box(0.06f), 0.9f),
        city = AddressPartValue("Beispielstadt", 2, box(0.06f), 0.9f),
        formatId = "DE",
        verified = true,
    )

    private fun adapted() = runBlocking {
        val letter = Letters.n1
        val oracle = Oracle.structured(letter, Prepared(letter.pages))
        val result = ExtractionV2Pipeline().run(letter.pages, ScriptedInterpreter(oracle.json, Oracle.text(letter)), 4096)
        result to adapter.adapt(result.copy(addresses = mapOf(PartyRole.ADDRESSEE to addressee, PartyRole.SENDER to sender)))
    }

    @Test
    fun `the addresses become addressee and sender rows with provenance, next to the unchanged party rows`() {
        val (result, withAddresses) = adapted()
        val without = adapter.adapt(result)
        val byLabel = withAddresses.facts.associateBy { it.label }

        assertThat(byLabel.keys).containsAtLeast(
            "addressee.name", "addressee.po_box", "addressee.postcode", "addressee.city", "addressee.country", "addressee.raw",
            "sender.organisation", "sender.street", "sender.house_number", "sender.postcode", "sender.city", "sender.raw",
        )
        assertThat(byLabel.keys).doesNotContain("addressee.street")
        assertThat(byLabel.getValue("addressee.postcode").value).isEqualTo("54321")
        assertThat(byLabel.getValue("addressee.name").value).isEqualTo("Max Mustermann; Erika Mustermann")
        // a row's confidence is its weakest name's
        assertThat(byLabel.getValue("addressee.name").confidence).isEqualTo(0.7f)
        assertThat(byLabel.getValue("addressee.raw").value).isEqualTo(addressee.lines.joinToString("\n"))

        val p = byLabel.getValue("sender.street").provenance!!
        assertThat(p.slotKey).isEqualTo("sender.street")
        assertThat(p.role).isEqualTo("SENDER")
        assertThat(p.origin).isEqualTo(AddressRows.ORIGIN)
        assertThat(p.page).isEqualTo(1)
        assertThat(p.bbox).isEqualTo(box(0.04f))

        // the existing name rows and every other fact are unchanged
        assertThat(withAddresses.entities).isEqualTo(without.entities)
        assertThat(withAddresses.facts.filter { !AddressRows.isAddressKey(it.provenance?.slotKey) }).isEqualTo(without.facts)
    }

    @Test
    fun `the stored fields keep the keys, the confidence and the page and position`() {
        val fields = UnderstandingToFields.invoke("doc", adapted().second) { it }
        val postcode = fields.single { it.slotKey == "addressee.postcode" }
        assertThat(postcode.fieldName).isEqualTo("addressee.postcode")
        assertThat(postcode.fieldType).isEqualTo(ExtractedFieldType.ADDRESS)
        assertThat(postcode.pageNumber).isEqualTo(1)
        assertThat(postcode.bbox).isEqualTo(box(0.16f))
        assertThat(postcode.confidence).isEqualTo(0.9f)
        assertThat(postcode.role).isEqualTo("ADDRESSEE")
        assertThat(fields.single { it.slotKey == "addressee.name" }.fieldType).isEqualTo(ExtractedFieldType.PERSON_NAME)
        // the party rows of the letter stay next to them
        assertThat(fields.map { it.slotKey }).containsAtLeast("sender", "addressee")
        assertThat(fields.count { it.slotKey == "addressee" }).isEqualTo(1)
    }

    @Test
    fun `a result without addresses adapts exactly as before`() {
        val (result, _) = adapted()
        assertThat(result.addresses).isEmpty()
        assertThat(adapter.adapt(result).facts.none { AddressRows.isAddressKey(it.provenance?.slotKey) }).isTrue()
    }

    @Test
    fun `a locker has its own row and a part a letter does not print has no row`() {
        val locker = PostalAddress(lines = listOf("Packstation 123"), packstation = AddressPartValue("Packstation 123", 0, box(0.1f), 0.9f))
        val rows = AddressRows.rows(PartyRole.ADDRESSEE, locker)
        assertThat(rows.map { it.key }).containsExactly("addressee.packstation", "addressee.raw")
        assertThat(AddressRows.rows(PartyRole.SUBJECT_PERSON, locker)).isEmpty()
    }

    @Test
    fun `every stored part key is owned by the address part enum`() {
        val keys = AddressRows.rows(PartyRole.ADDRESSEE, addressee).map { it.key.removePrefix("addressee.") }
        assertThat(AddressPart.entries.map { it.key } + AddressRows.RAW).containsAtLeastElementsIn(keys)
    }

    @Test
    fun `a postal address survives serialization`() {
        val json = Json
        val text = json.encodeToString(PostalAddress.serializer(), addressee)
        assertThat(json.decodeFromString(PostalAddress.serializer(), text)).isEqualTo(addressee)
    }

    @Test
    fun `a labelled and verified address flows to rows end to end`() {
        val labeled = AddressLineLabeler().shape(
            listOf(
                com.postsaimanager.core.domain.extraction.address.line("Musterstraße 12", 0.12f),
                com.postsaimanager.core.domain.extraction.address.line("54321 Beispieldorf", 0.14f),
            ),
        )
        val rows = AddressRows.rows(PartyRole.ADDRESSEE, AddressVerifier().verify(labeled))
        assertThat(rows.map { it.key }).containsAtLeast("addressee.street", "addressee.house_number", "addressee.postcode", "addressee.city", "addressee.country")
    }
}
