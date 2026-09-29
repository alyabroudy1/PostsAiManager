package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FactKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ExtractionV2AdapterTest {

    private val adapter = ExtractionV2Adapter()
    private val pipeline = ExtractionV2Pipeline()

    private fun read(letter: Letter): ExtractionV2Result = runBlocking {
        val oracle = Oracle.structured(letter, Prepared(letter.pages))
        pipeline.run(letter.pages, ScriptedInterpreter(oracle.json, Oracle.text(letter)), 4096)
    }

    private fun understand(letter: Letter): DocumentUnderstanding = adapter.adapt(read(letter))

    private fun fields(u: DocumentUnderstanding) = UnderstandingToFields.invoke("doc", u) { it }

    @Test
    fun `the slots that feed existing fields take their names`() {
        val u = understand(Letters.invoice)
        val byLabel = u.facts.associateBy { it.label }
        assertThat(byLabel.getValue("Amount").value).isEqualTo("1.284,50 €")
        assertThat(byLabel.getValue("Amount").kind).isEqualTo(FactKind.AMOUNT)
        assertThat(byLabel.getValue("Deadline").value).isEqualTo("15.10.2026")
        assertThat(byLabel.getValue("Deadline").kind).isEqualTo(FactKind.DEADLINE)
        assertThat(byLabel.getValue("Document Date").value).isEqualTo("28.09.2026")
        assertThat(byLabel.getValue("IBAN").value).startsWith("DE89")
        assertThat(byLabel.getValue("Invoice Number").kind).isEqualTo(FactKind.REFERENCE)
        assertThat(byLabel.getValue("Customer Number").value).isEqualTo("KD-40417")
    }

    @Test
    fun `several amounts are several labelled fields, the first main one is the Amount`() {
        val u = understand(Letters.n1)
        val labels = u.facts.map { it.label }
        assertThat(labels).containsAtLeast("Amount", "Fee", "Original Due Date", "Deadline", "Document Date", "IBAN")
        assertThat(u.facts.single { it.label == "Amount" }.value).isEqualTo("64,98 €")
        assertThat(u.facts.single { it.label == "Fee" }.value).isEqualTo("5,00")
        // the relative deadline is the Deadline, as printed
        assertThat(u.facts.single { it.label == "Deadline" }.value).contains("14 Tagen")
        assertThat(labels.toSet().size).isEqualTo(labels.size)
    }

    @Test
    fun `an insurance letter keeps the new and the previous premium apart`() {
        val u = understand(Letters.n2)
        assertThat(u.facts.single { it.label == "Amount" }.value).isEqualTo("612,40 €")
        assertThat(u.facts.single { it.label == "Previous Amount" }.value).isEqualTo("579,10 €")
        assertThat(u.facts.single { it.label == "Contract End" }.value).isEqualTo("31.12.2026")
    }

    @Test
    fun `the document type, language, title, summary and questions are carried`() {
        val u = understand(Letters.n1)
        assertThat(u.documentType).isEqualTo("reminder_dunning")
        assertThat(u.documentTypeConfidence).isEqualTo(0.9f)
        assertThat(u.language).isEqualTo("de")
        assertThat(u.title).contains("Nordlicht")
        assertThat(u.summary).contains("64,98")
        assertThat(u.suggestedQuestions).hasSize(3)
        assertThat(u.modelUsed).isTrue()
    }

    @Test
    fun `the subject is a fact with its own confidence, not a fixed 0_9`() {
        val f = fields(understand(Letters.n1))
        val subject = f.single { it.fieldName == "Subject" }
        assertThat(subject.fieldType).isEqualTo(ExtractedFieldType.SUBJECT)
        assertThat(subject.confidence).isAtMost(0.6f)
        assertThat(subject.fieldValue).startsWith("Zahlungserinnerung")
    }

    @Test
    fun `the summary is the document's, not a field`() {
        val u = understand(Letters.n1)
        assertThat(u.summary).contains("64,98")
        assertThat(fields(u).map { it.fieldName }).doesNotContain("Content Preview")
    }

    @Test
    fun `every stored field carries its slot key, role, origin, both confidences, evidence and page`() {
        val result = read(Letters.n1)
        val f = fields(adapter.adapt(result)).associateBy { it.slotKey }
        val total = f.getValue("total")
        assertThat(total.fieldName).isEqualTo("Amount")
        assertThat(total.origin).isEqualTo("MODEL_CHOICE")
        assertThat(total.role).isNotNull()
        assertThat(total.aiConfidence).isNotNull()
        // The final confidence is never above the model's own.
        assertThat(total.confidence).isAtMost(total.aiConfidence!!)
        assertThat(total.evidence).isNotEmpty()
        assertThat(total.pageNumber).isNotNull()
        assertThat(total.bbox).isNotNull()
        assertThat(f.keys).containsAtLeast("due_date", "letter_date", "iban", "sender", "addressee", "subject")
        // What is stored is what the verifier produced, straight through.
        assertThat(total.confidence).isEqualTo(result.slots.getValue(Slots.TOTAL).confidence)
        assertThat(total.aiConfidence).isEqualTo(result.slots.getValue(Slots.TOTAL).aiConfidence)
    }

    @Test
    fun `the sender is one slot whether it is a name or an organisation, and extras are keyed by their printed label`() {
        val f = fields(understand(Letters.n6))
        assertThat(f.single { it.slotKey == "sender" }.fieldName).isIn(listOf("Sender Organization", "Sender Name"))
        val extra = f.single { it.fieldName == "Geleistete Vorauszahlungen" }
        assertThat(extra.slotKey).isEqualTo("x:geleistete_vorauszahlungen")
    }

    @Test
    fun `found values are marked as found and keyed found KIND N, with no English stored`() {
        val bare = runBlocking { pipeline.run(Letters.n1.pages, null, 4096) }
        val stored = fields(adapter.adapt(bare))
        assertThat(stored).isNotEmpty()
        assertThat(stored.all { it.origin == "FOUND" }).isTrue()
        // The key is both identity and stored name; the screen words it from a string resource.
        assertThat(stored.all { ExtractionV2Adapter.parseFoundKey(it.slotKey) != null }).isTrue()
        assertThat(stored.all { it.fieldName == it.slotKey }).isTrue()
        assertThat(stored.map { it.slotKey }).contains("found:AMOUNT:1")
        assertThat(stored.map { it.slotKey }.toSet()).hasSize(stored.size)
    }

    @Test
    fun `a found key round-trips and rejects anything else`() {
        assertThat(ExtractionV2Adapter.parseFoundKey(ExtractionV2Adapter.foundKey(CandidateKind.IBAN, 2)))
            .isEqualTo(CandidateKind.IBAN to 2)
        assertThat(ExtractionV2Adapter.parseFoundKey("total")).isNull()
        assertThat(ExtractionV2Adapter.parseFoundKey("found:NOPE:1")).isNull()
        assertThat(ExtractionV2Adapter.parseFoundKey("found:DATE:x")).isNull()
        assertThat(ExtractionV2Adapter.parseFoundKey(null)).isNull()
    }

    @Test
    fun `entities carry the roles the model decided`() {
        val u = understand(Letters.n1)
        val sender = u.entities.single { it.role == EntityRole.SENDER }
        assertThat(sender.name).isEqualTo("Nordlicht Mobilfunk GmbH")
        assertThat(sender.kind).isEqualTo(EntityKind.COMPANY)
        val recipient = u.entities.single { it.role == EntityRole.RECIPIENT }
        assertThat(recipient.name).isEqualTo("Erika Mustermann")
        assertThat(recipient.kind).isEqualTo(EntityKind.PERSON)
        assertThat(recipient.confidence).isAtLeast(0.75f)
    }

    @Test
    fun `co-addressees are recipients, and nobody is listed twice`() {
        val u = understand(Letters.n2)
        assertThat(u.entities.filter { it.role == EntityRole.RECIPIENT }.map { it.name }).containsExactly("Max Mustermann", "Erika Mustermann")
        // the subject persons are the same two people: not repeated as "mentioned"
        assertThat(u.entities.none { it.role == EntityRole.MENTIONED }).isTrue()
        assertThat(u.entities.single { it.name == "Max Mustermann" }.relation).isEqualTo("household")
    }

    @Test
    fun `a routing person and a mailbox are mentioned, never recipients`() {
        val n4 = understand(Letters.n4)
        assertThat(n4.entities.single { it.role == EntityRole.RECIPIENT }.name).isEqualTo("Mustermann Consulting GmbH")
        assertThat(n4.entities.single { it.name == "Erika Mustermann" }.role).isEqualTo(EntityRole.MENTIONED)
        val n5 = understand(Letters.n5)
        assertThat(n5.entities.single { it.role == EntityRole.RECIPIENT }.name).isEqualTo("Jonas Mustermann")
        assertThat(n5.entities.single { it.name == "Familie Beispiel" }.role).isEqualTo(EntityRole.MENTIONED)
        assertThat(n5.entities.single { it.name == "Familie Beispiel" }.relation).contains("care of")
    }

    @Test
    fun `when a guardian is addressed the profile to link is the child`() {
        val u = understand(Letters.n10)
        val recipient = u.entities.single { it.role == EntityRole.RECIPIENT }
        assertThat(recipient.name).isEqualTo("Adam Mustermann")
        assertThat(recipient.relation).contains("guardian")
    }

    @Test
    fun `a household is a recipient under its own name`() {
        val u = understand(Letters.n3)
        assertThat(u.entities.single { it.role == EntityRole.RECIPIENT }.name).isEqualTo("Familie Mustermann")
        assertThat(u.entities.single { it.name == "Lea Mustermann" }.role).isEqualTo(EntityRole.MENTIONED)
    }

    @Test
    fun `extras appear as fields with the model's own label`() {
        val u = understand(Letters.n6)
        val labels = u.facts.map { it.label }
        assertThat(labels).containsAtLeast("Summe Ihrer Betriebskosten", "Geleistete Vorauszahlungen", "monatliche Betriebskostenvorauszahlung")
        val f = fields(u)
        assertThat(f.single { it.fieldName == "Geleistete Vorauszahlungen" }.fieldValue).isEqualTo("2.640,00")
        // fixed slots are still there beside them
        assertThat(f.map { it.fieldName }).containsAtLeast("Amount", "Deadline", "IBAN")
    }

    @Test
    fun `an extra whose label collides with a field is stored under its slot key instead of overwriting it`() {
        val base = read(Letters.n1)
        val extra = ExtraValue("Amount", "some_amount", base.slots.getValue(Slots.FEE))
        val u = adapter.adapt(base.copy(extras = listOf(extra)))
        // Disambiguated by the slot key alone: nothing is appended to the printed label.
        assertThat(u.facts.map { it.label }).containsAtLeast("Amount", "x:amount")
        assertThat(u.facts.map { it.label }.none { it.contains("some_amount") }).isTrue()
        assertThat(u.facts.single { it.label == "Amount" }.value).isEqualTo("64,98 €")
        assertThat(u.facts.single { it.label == "x:amount" }.provenance?.slotKey).isEqualTo("x:amount")
    }

    @Test
    fun `the confidence carried is the verified one`() {
        val u = understand(Letters.n4)
        // "Netto p. a." amount has no currency next to the number in the table cell? capped or not, never above the model's 0.9
        assertThat(u.facts.all { it.confidence in 0f..0.9f }).isTrue()
        val iban = understand(Letters.n5).facts.single { it.label == "IBAN" }
        assertThat(iban.confidence).isEqualTo(0.9f)
    }

    @Test
    fun `without a model the found values become found fields with low confidence, no roles, no type`() {
        val bare = runBlocking { pipeline.run(Letters.n1.pages, null, 4096) }
        val u = adapter.adapt(bare)
        assertThat(u.modelUsed).isFalse()
        assertThat(u.documentType).isEmpty()
        assertThat(u.entities).isEmpty()
        assertThat(u.title).isEmpty()
        assertThat(u.facts).isNotEmpty()
        assertThat(u.facts.all { it.label.startsWith(ExtractionV2Adapter.FOUND_KEY_PREFIX) }).isTrue()
        assertThat(u.facts.all { it.kind == FactKind.OTHER }).isTrue()
        assertThat(u.facts.all { it.confidence <= 0.3f }).isTrue()
        assertThat(u.facts.map { it.label }).contains("found:AMOUNT:1")
        // no label such as "Amount" or "Deadline": nothing is guessed about what a value means
        assertThat(u.facts.map { it.label }).containsNoneOf("Amount", "Deadline", "IBAN", "Document Date")
    }

    @Test
    fun `a truncated letter is recorded`() {
        val base = read(Letters.n1)
        val cut = base.copy(diagnostics = base.diagnostics.copy(layoutCharsSent = 100, layoutCharsTotal = 400, pagesRead = 1, totalPages = 3))
        val t = adapter.adapt(cut).inputTruncation!!
        assertThat(t.charactersRead).isEqualTo(100)
        assertThat(t.totalCharacters).isEqualTo(400)
        assertThat(t.totalPages).isEqualTo(3)
        assertThat(adapter.adapt(base).inputTruncation).isNull()
    }

    @Test
    fun `document types map to the app's stored type`() {
        for ((letter, legacy) in listOf(
            Letters.invoice to com.postsaimanager.core.model.DocumentType.INVOICE,
            Letters.tax to com.postsaimanager.core.model.DocumentType.OFFICIAL_LETTER,
            Letters.receipt to com.postsaimanager.core.model.DocumentType.RECEIPT,
        )) {
            assertThat(ExtractionSchema.DEFAULT.legacyType(understand(letter).documentType)).isEqualTo(legacy)
        }
    }
}
