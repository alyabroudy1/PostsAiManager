package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.contacts.ContactLinkOutcome
import com.postsaimanager.core.domain.contacts.LinkSenderContactUseCase
import com.postsaimanager.core.domain.document.contacts.DecideSameContactUseCase
import com.postsaimanager.core.domain.document.contacts.SameContact
import com.postsaimanager.core.domain.document.contacts.SameContactProfile
import com.postsaimanager.core.domain.document.contacts.SameContactQuestion
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.Din
import com.postsaimanager.core.domain.form.BaselineScores
import com.postsaimanager.core.domain.organisation.DecideDetailOwnerUseCase
import com.postsaimanager.core.domain.organisation.DetailOwnerProfile
import com.postsaimanager.core.domain.organisation.SuggestOrganisationDetailsUseCase
import com.postsaimanager.core.domain.timeline.EventDateResolver
import com.postsaimanager.core.domain.timeline.EventKinds
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.SuggestionField
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeDetailOwnerQuestion
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.FakeProfileSuggestionRepository
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Jobcenter letter (the device pass's invented letter) read by Gemma, end to end up to what a person sees: the reading is the real
 * use case with a scripted model answer; what it stores, the contact linking, the organisation suggestions and the timeline event's date
 * are the real classes over fake repositories. It is the one place that shows the Gemma mapping gives the after-reading steps what they need.
 */
class GemmaAfterReadingTest {

    private val letterBlocks: List<OcrBlock> = Din.firstPage(
        Din.Sender(
            letterhead = listOf("Jobcenter Musterstadt", "Musterstraße 1", "12345 Musterstadt"),
            returnLine = "Jobcenter Musterstadt · Musterstraße 1 · 12345 Musterstadt",
            footer = emptyList(),
        ),
        address = listOf("Maria Mustermann", "Beispielweg 2", "12345 Musterstadt"),
        info = listOf(
            "BG-Nummer" to "12345BG0007777",
            "Ansprechpartnerin" to "Frau Nadine Beispiel",
            "Telefon" to "0123 456-701",
            "E-Mail" to "nadine.beispiel@jobcenter-musterstadt.example",
            "Datum" to "01.09.2026",
        ),
        subject = "Eingangsbestätigung Ihres Antrags auf Bürgergeld – BG-Nummer 12345BG0007777",
        body = listOf(
            "Sehr geehrte Frau Mustermann,",
            "wir bestätigen, dass Ihr Antrag auf Bürgergeld am 01.09.2026 bei uns eingegangen ist. Wir prüfen Ihren Antrag und melden uns, sobald eine Entscheidung vorliegt.",
            "Bitte geben Sie bei allen Rückfragen Ihre BG-Nummer an.",
            "Mit freundlichen Grüßen",
            "Nadine Beispiel",
            "Jobcenter Musterstadt",
        ),
    )

    private val provider = FakeActiveModelProvider(runtime = ModelRuntime.LITERT_LM, supportsImages = true)

    /** What Gemma answered on the phone: the date it saw as a due date (its meaning), the contact it named. */
    private fun answerOf(l: GemmaLetter, dateMeaning: String): String = answer(
        mapOf(
            "sender" to party(l.idOf(CandidateKind.NAME, "Jobcenter Musterstadt"), "authority"),
            "addressee" to party(l.idOf(CandidateKind.NAME, "Maria Mustermann")),
            "contact" to party(l.idOf(CandidateKind.NAME, "Nadine Beispiel")),
            "dates" to arr(value(l.idOf(CandidateKind.DATE, "01.09.2026"), dateMeaning)),
            "references" to arr(obj("candidateId" to str(l.idOf(CandidateKind.REFERENCE, "12345BG0007777")), "kind" to str("case_no"))),
            "category" to str("letter"),
            "eventKind" to str(EventKinds.APPLICATION_FILED),
            "name" to str("Bürgergeld Eingangsbestätigung"),
            "summary" to str("Das Jobcenter bestätigt den Eingang des Antrags auf Bürgergeld."),
        ),
    )

    private val documents = FakeDocumentRepository()
    private val profiles = FakeProfileRepository()
    private val contacts = FakeContactRepository()
    private val suggestions = FakeProfileSuggestionRepository()
    private val owner = FakeDetailOwnerQuestion()

    private class SameContactYes : SameContact {
        override suspend fun score(question: SameContactQuestion): PamResult<BaselineScores> =
            PamResult.Success(BaselineScores(question.candidates.map { -4.0 }, 0.0))
    }

    private val scannedAt = LocalDate.of(2026, 10, 8).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Reads the letter as Gemma and stores what the pipeline would (the fields, the pages' text), with the sender organisation linked. */
    private fun readAndStore(dateMeaning: String): List<com.postsaimanager.core.model.ExtractedData> = runBlocking {
        val outcome = GemmaReadingUseCase(ScriptedReader.answering { answerOf(it, dateMeaning) }, EntityAnnotator.NONE, provider)(
            listOf(letterBlocks), listOf("/p1.png"), pageAspect = 0.707f,
        ) as GemmaReadingOutcome.Read
        val understanding = outcome.understanding
        val fields = UnderstandingToFields.invoke("d1", understanding) { UuidGenerator.generate() }
        documents.seed(testDocument(id = "d1", createdAt = scannedAt))
        documents.seedPages("d1", DocumentPage("p1", "d1", 1, "file:///1.jpg", ocrText = letterBlocks.joinToString("\n") { it.text }))
        documents.seedExtracted("d1", *fields.toTypedArray())
        // The sender entity is what the profile linking turns into the organisation profile linked as the sender.
        assertThat(understanding.entities.map { it.role }).contains(EntityRole.SENDER)
        profiles.seed(testProfile(id = "jc", name = "Jobcenter Musterstadt", organization = "Jobcenter Musterstadt", kind = ProfileKind.ORGANISATION))
        profiles.linkProfileToDocument("jc", "d1", ProfileRole.SENDER)
        fields
    }

    @Test
    @DisplayName("the contact Gemma named is stored as the letter's contact field and linked to the sender organisation")
    fun `contact is suggested`() = runBlocking {
        val fields = readAndStore("DUE_DATE")

        assertThat(fields.filter { it.slotKey == UnderstandingToFields.SLOT_CONTACT }.map { it.fieldValue }).containsExactly("Nadine Beispiel")
        val link = LinkSenderContactUseCase(documents, profiles, contacts, DecideSameContactUseCase(SameContactYes(), SameContactProfile()))
        val outcome = link("d1")

        assertThat(outcome).isInstanceOf(ContactLinkOutcome.Created::class.java)
        assertThat(contacts.observeContacts("jc").first().map { it.name }).containsExactly("Nadine Beispiel")
    }

    @Test
    @DisplayName("the organisation's address, phone and e-mail from the letter are offered as suggestions after a Gemma reading")
    fun `organisation details are suggested`() = runBlocking {
        val fields = readAndStore("DUE_DATE")
        owner.organisationValues = setOf("0123 456-701", "nadine.beispiel@jobcenter-musterstadt.example")
        val suggest = SuggestOrganisationDetailsUseCase(documents, profiles, contacts, suggestions, DecideDetailOwnerUseCase(owner, DetailOwnerProfile()))

        val outcome = suggest("d1")

        val pending = suggestions.observePending("jc").first().map { it.field }
        assertThat(outcome.skipped).isFalse()
        assertThat(pending).contains(SuggestionField.ADDRESS)
        assertThat(pending).contains(SuggestionField.PHONE)
        assertThat(pending).contains(SuggestionField.EMAIL)
        assertThat(fields.any { it.slotKey?.startsWith("sender.") == true }).isTrue()
    }

    @Test
    @DisplayName("the event is dated by the letter's date, though the reader gave the date another meaning")
    fun `event date is the letter date`() {
        val fields = readAndStore("DUE_DATE")

        val day = EventDateResolver.resolve(EventKinds.DEFAULT.byId(EventKinds.APPLICATION_FILED), fields, scannedAt, ZoneOffset.UTC)

        assertThat(java.time.Instant.ofEpochMilli(day).atZone(ZoneOffset.UTC).toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 1))
    }

    @Test
    @DisplayName("a date the reader named the letter date dates the event, as before")
    fun `event date from the named letter date`() {
        val fields = readAndStore("LETTER_DATE")

        val day = EventDateResolver.resolve(EventKinds.DEFAULT.byId(EventKinds.APPLICATION_FILED), fields, scannedAt, ZoneOffset.UTC)

        assertThat(java.time.Instant.ofEpochMilli(day).atZone(ZoneOffset.UTC).toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 1))
    }
}
