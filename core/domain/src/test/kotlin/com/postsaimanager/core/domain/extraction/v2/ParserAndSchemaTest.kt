package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.model.DocumentType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ParserAndSchemaTest {

    private val structured = """
        {"type":"school","tc":"HIGH","lang":"de",
         "parties":[{"r":"SENDER","id":"M1","k":"AUTHORITY","rel":"NONE","c":"HIGH"},
                    {"r":"ADDRESSEE","id":"M2","k":"PERSON","rel":"HOUSEHOLD","c":"MEDIUM"}],
         "s":{"letter_date":{"id":"D1","r":"LETTER_DATE","c":"HIGH"},"total":"NONE",
              "due_date":{"rule":"innerhalb eines Monats","r":"DEADLINE","c":"LOW"},
              "iban":{"id":"I1","c":"HIGH"},"cited_references":{"ids":["N1","N2"],"c":"MEDIUM"}},
         "x":[{"lb":"Klasse","k":"school_class","id":"NONE","v":"2a","c":"MEDIUM"}]}
    """.trimIndent()

    @Test
    fun `call 1's answer is read into parties, slots and extras`() {
        val raw = (InterpretationParser.parse(structured) as InterpretationParser.Parsed.Ok).value
        assertThat(raw.type).isEqualTo("school")
        assertThat(raw.typeConfidence).isEqualTo("HIGH")
        assertThat(raw.language).isEqualTo("de")
        assertThat(raw.parties.map { it.role to it.id }).containsExactly("SENDER" to "M1", "ADDRESSEE" to "M2").inOrder()
        assertThat(raw.parties[1].relation).isEqualTo("HOUSEHOLD")
        assertThat(raw.parties[1].confidence).isEqualTo("MEDIUM")
        assertThat(raw.slots.keys).containsExactly("letter_date", "due_date", "iban", "cited_references")
        assertThat(raw.slots.getValue("letter_date").role).isEqualTo("LETTER_DATE")
        assertThat(raw.slots.getValue("due_date").rule).isEqualTo("innerhalb eines Monats")
        assertThat(raw.slots.getValue("cited_references").ids).containsExactly("N1", "N2")
        assertThat(raw.extras.single().value).isEqualTo("2a")
    }

    @Test
    fun `a slot answered NONE is simply absent`() {
        val raw = (InterpretationParser.parse(structured) as InterpretationParser.Parsed.Ok).value
        assertThat(raw.slots).doesNotContainKey("total")
    }

    @Test
    fun `text around the JSON is ignored`() {
        val raw = InterpretationParser.parse("Here you go: $structured -- done")
        assertThat(raw).isInstanceOf(InterpretationParser.Parsed.Ok::class.java)
    }

    @Test
    fun `garbage and a cut-off answer are reported, not thrown`() {
        assertThat(InterpretationParser.parse("no json here")).isInstanceOf(InterpretationParser.Parsed.Bad::class.java)
        assertThat(InterpretationParser.parse("""{"tc":"HI""")).isInstanceOf(InterpretationParser.Parsed.Bad::class.java)
        assertThat(InterpretationParser.parse("""{"tc":"HIGH"}""")).isInstanceOf(InterpretationParser.Parsed.Bad::class.java)
    }

    @Test
    fun `call 2's answer is read into the free text`() {
        val text = """{"other":"","title":"Schule: Ausflug","subject":"Ausflug","summary":"Kostet 12 €.","qs":["a?","b?","c?"]}"""
        val t = (InterpretationParser.parseText(text) as InterpretationParser.Parsed.Ok).value
        assertThat(t.title).isEqualTo("Schule: Ausflug")
        assertThat(t.questions).containsExactly("a?", "b?", "c?").inOrder()
        assertThat(InterpretationParser.parseText("nope")).isInstanceOf(InterpretationParser.Parsed.Bad::class.java)
    }

    // ── the schema is data ──

    @Test
    fun `every document type has the universal core, first`() {
        for (t in ExtractionSchema.DEFAULT.families) assertThat(t.slots.take(Slots.CORE.size)).isEqualTo(Slots.CORE)
    }

    @Test
    fun `outgoing letters and payment proofs are defined for P3 with their own slots`() {
        assertThat(ExtractionSchema.OUTGOING_LETTER.slots).containsAtLeast(
            Slots.RECIPIENT_ORG, Slots.SENT_DATE, Slots.ACTION_KIND, Slots.CITED_REFERENCES,
        )
        assertThat(ExtractionSchema.PAYMENT_PROOF.slots).containsAtLeast(
            Slots.PROOF_AMOUNT, Slots.PROOF_DATE, Slots.PROOF_RECIPIENT, Slots.PROOF_REFERENCE,
        )
    }

    @Test
    fun `the eleven types are the agreed ones`() {
        assertThat(ExtractionSchema.DEFAULT.families.map { it.id }).containsExactly(
            "bill", "reminder_dunning", "authority_tax", "health", "insurance_contract", "school", "receipt",
            "info_no_action", "outgoing_letter", "payment_proof", "other",
        )
    }

    @Test
    fun `a duplicate type id is refused`() {
        val t = DocFamily.of("x", DocumentType.OTHER)
        val e = runCatching { ExtractionSchema(listOf(t, t)) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `adding a document type is one line and the grammar, prompt and verifier follow`() = runTest {
        // A new type: the core plus one slot of its own. Nothing else is touched.
        val energy = DocFamily.of("energy_tariff", DocumentType.CONTRACT, Slots.CONTRACT_NO)
        val schema = ExtractionSchema(ExtractionSchema.DEFAULT.families + energy)

        val prepared = Prepared(Letters.n1.pages)
        val grammar = StructuredGrammar.build(prepared.offered, schema)
        assertThat(grammar).contains("t-energy-tariff ::=")
        assertThat(SelectionPrompt.system(schema, withExample = false)).contains("energy_tariff")

        val customer = prepared.find(CandidateKind.REFERENCE, "4402917")!!
        val json = """{"type":"energy_tariff","tc":"HIGH","lang":"de","parties":[],"s":{"customer_no":{"id":"${customer.id}","c":"HIGH"}},"x":[]}"""
        val raw = (InterpretationParser.parse(json) as InterpretationParser.Parsed.Ok).value
        val result = SelectionVerifier(schema).verify(
            raw, null,
            VerificationContext(prepared.candidates, prepared.offered, listOf("4402917"), 0, 0, 1, 1),
        )
        assertThat(result.documentType).isEqualTo(energy)
        assertThat(result.slots[Slots.CUSTOMER_NO]?.normalized).isEqualTo("4402917")
        assertThat(ExtractionSchema.DEFAULT.family("energy_tariff")).isNull()
    }

    @Test
    fun `legacy types map to the app's stored document type`() {
        assertThat(ExtractionSchema.DEFAULT.legacyType("bill")).isEqualTo(DocumentType.INVOICE)
        assertThat(ExtractionSchema.DEFAULT.legacyType("authority_tax")).isEqualTo(DocumentType.OFFICIAL_LETTER)
        assertThat(ExtractionSchema.DEFAULT.legacyType("insurance_contract")).isEqualTo(DocumentType.CONTRACT)
        assertThat(ExtractionSchema.DEFAULT.legacyType("receipt")).isEqualTo(DocumentType.RECEIPT)
        assertThat(ExtractionSchema.DEFAULT.legacyType("nonsense")).isNull()
        assertThat(ExtractionSchema.DEFAULT.legacyType(null)).isNull()
    }
}
