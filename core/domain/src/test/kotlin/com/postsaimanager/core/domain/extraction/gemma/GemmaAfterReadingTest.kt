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
import com.postsaimanager.core.testing.scoringFollowUps
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

    private val letterBlocks: List<OcrBlock> = DeviceLetters.jobcenterBlocks

    private val provider = FakeActiveModelProvider(runtime = ModelRuntime.LITERT_LM, supportsImages = true)

    /**
     * What Gemma answered on the phone: the date it saw as a due date (its meaning), the contact it named. With [byLine] the sender and
     * the contact are named by a line of the letter, not by a name candidate (the way the phone's answers named them).
     */
    private fun answerOf(l: GemmaLetter, dateMeaning: String, byLine: Boolean = false): String = answer(
        mapOf(
            "sender" to party(if (byLine) l.lineOf("Jobcenter Musterstadt") else l.idOf(CandidateKind.NAME, "Jobcenter Musterstadt"), "authority"),
            "addressee" to party(l.idOf(CandidateKind.NAME, "Maria Mustermann")),
            "contact" to party(if (byLine) l.lineOf("Frau Nadine Beispiel") else l.idOf(CandidateKind.NAME, "Nadine Beispiel")),
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
    private fun readAndStore(dateMeaning: String, byLine: Boolean = false): List<com.postsaimanager.core.model.ExtractedData> = runBlocking {
        val outcome = GemmaReadingUseCase(ScriptedReader.answering { answerOf(it, dateMeaning, byLine) }, EntityAnnotator.NONE, provider)(
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
        val link = LinkSenderContactUseCase(documents, profiles, contacts, DecideSameContactUseCase(scoringFollowUps(sameContact = SameContactYes()), SameContactProfile()))
        val outcome = link("d1")

        assertThat(outcome).isInstanceOf(ContactLinkOutcome.Created::class.java)
        assertThat(contacts.observeContacts("jc").first().map { it.name }).containsExactly("Nadine Beispiel")
    }

    @Test
    @DisplayName("a sender and a contact the model named by a line of the letter are live (at or above the link threshold), and the contact is linked")
    fun `parties named by a line are live`() = runBlocking {
        val fields = readAndStore("LETTER_DATE", byLine = true)

        val sender = fields.first { it.slotKey == UnderstandingToFields.SLOT_SENDER }
        val contact = fields.first { it.slotKey == UnderstandingToFields.SLOT_CONTACT }
        // The old scoring reading capped a quoted party at 0.6, under the 0.75 the linking needs: nothing was linked.
        assertThat(sender.confidence).isAtLeast(0.75f)
        assertThat(contact.confidence).isAtLeast(0.75f)
        assertThat(contact.fieldValue).contains("Nadine Beispiel")

        val link = LinkSenderContactUseCase(documents, profiles, contacts, DecideSameContactUseCase(scoringFollowUps(sameContact = SameContactYes()), SameContactProfile()))
        assertThat(link("d1")).isInstanceOf(ContactLinkOutcome.Created::class.java)
        assertThat(contacts.observeContacts("jc").first().map { it.name }.single()).contains("Nadine Beispiel")
    }

    @Test
    @DisplayName("the organisation's address, phone and e-mail from the letter are offered as suggestions after a Gemma reading")
    fun `organisation details are suggested`() = runBlocking {
        val fields = readAndStore("DUE_DATE")
        owner.organisationValues = setOf("0123 456-701", "nadine.beispiel@jobcenter-musterstadt.example")
        val suggest = SuggestOrganisationDetailsUseCase(documents, profiles, contacts, suggestions, DecideDetailOwnerUseCase(scoringFollowUps(detailOwner = owner)))

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
