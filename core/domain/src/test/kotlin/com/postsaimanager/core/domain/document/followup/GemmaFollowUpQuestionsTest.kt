package com.postsaimanager.core.domain.document.followup

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.StructuredRequest
import com.postsaimanager.core.domain.contacts.ContactLinkOutcome
import com.postsaimanager.core.domain.contacts.LinkSenderContactUseCase
import com.postsaimanager.core.domain.document.contacts.ContactCandidate
import com.postsaimanager.core.domain.document.contacts.DecideSameContactUseCase
import com.postsaimanager.core.domain.document.contacts.ReadContact
import com.postsaimanager.core.domain.document.contacts.SameContact
import com.postsaimanager.core.domain.document.contacts.SameContactProfile
import com.postsaimanager.core.domain.document.contacts.SameContactQuestion
import com.postsaimanager.core.domain.document.people.ConcernedPeopleProfile
import com.postsaimanager.core.domain.document.people.DecideConcernedPeopleUseCase
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.form.BaselineScores
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.organisation.DecideDetailOwnerUseCase
import com.postsaimanager.core.domain.organisation.DetailKind
import com.postsaimanager.core.domain.organisation.DetailOwner
import com.postsaimanager.core.domain.organisation.DetailOwnerProfile
import com.postsaimanager.core.domain.organisation.DetailQuestion
import com.postsaimanager.core.domain.organisation.SuggestOrganisationDetailsUseCase
import com.postsaimanager.core.domain.timeline.MatterCandidate
import com.postsaimanager.core.domain.timeline.NewMatterEvent
import com.postsaimanager.core.domain.timeline.SameMatterProfile
import com.postsaimanager.core.domain.timeline.SameMatterQuestion
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.SuggestionField
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeChatEngine
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeGemmaReaderTrial
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.FakeProfileSuggestionRepository
import com.postsaimanager.core.testing.scoringFollowUps
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.inject.Provider

/**
 * The after-reading questions as Gemma follow-up turns, with a fake engine: they run in the reader's own conversation, a made-up
 * candidate or "none" means no match, a fresh conversation with the letter's text stands in when the reader's is gone, nothing opens a
 * Qwen prompt session under the Gemma default, and a question that cannot be answered is an error (pending), never "no match".
 * The Jobcenter letter (an invented one) is the fixture of the use-case tests at the end.
 */
class GemmaFollowUpQuestionsTest {

    private val engine = FakeChatEngine()
    private val provider = FakeActiveModelProvider(runtime = ModelRuntime.LITERT_LM)
    private val documents = FakeDocumentRepository()
    private val profiles = FakeProfileRepository()
    private val contacts = FakeContactRepository()
    private val suggestions = FakeProfileSuggestionRepository()
    private val gemma = GemmaFollowUpQuestions(
        engine, provider, documents, ConcernedPeopleProfile(), SameContactProfile(), DetailOwnerProfile(), SameMatterProfile(),
    )

    private val letter = """
        Jobcenter Musterstadt
        Beispielweg 12 · 12345 Musterstadt
        Telefon: 0800 555 0199
        Sehr geehrte Frau Mustermann,
        wir bestätigen den Eingang Ihres Antrags auf Bürgergeld für Maria Mustermann.
        Mit freundlichen Grüßen
        Frau Nadine Beispiel
        Tel. 0800 555 0123
    """.trimIndent()

    private val nadine = ContactCandidate("nadine", "Frau Nadine Beispiel", "Sachbearbeiterin", phone = "0800 555 0123", lastSeenAt = 1_000)
    private val schmidt = ContactCandidate("schmidt", "Herr Karl Schmidt", lastSeenAt = 2_000)
    private val read = ReadContact("Nadine Beispiel", phone = "0800 555 0123")

    init {
        documents.seed(testDocument(id = "d1", createdAt = 1_000))
        documents.seedPages("d1", DocumentPage("p1", "d1", 1, "file:///1.jpg", ocrText = letter))
    }

    /** What the reader does: one structured answer, its conversation left open under the document's id. */
    private suspend fun theReaderReads() {
        engine.structuredAnswer = "{}"
        engine.generateStructured(StructuredRequest(system = "s", prompt = "p", schema = "{}", imagePaths = listOf("/p1.png"), keepOpenAs = "d1"))
        engine.structuredAnswer = null
    }

    private fun answers(json: String) {
        engine.followUpResponder = { json }
    }

    private val contactQuestion get() = SameContactQuestion(read, "Jobcenter Musterstadt", null, listOf(nadine, schmidt))

    @Test
    @DisplayName("a follow-up is asked in the reader's conversation: no new conversation is opened and the letter is not sent again")
    fun `runs in the readers conversation`() = runTest {
        theReaderReads()
        answers("""{"answer":"C1"}""")

        val decision = (gemma.sameContact("d1", contactQuestion) as PamResult.Success).data

        assertThat(decision.matchedId).isEqualTo("nadine")
        val asked = engine.followUpRequests.single()
        assertThat(asked.key).isEqualTo("d1")
        assertThat(asked.prompt).contains("Frau Nadine Beispiel")
        assertThat(asked.prompt).doesNotContain("LETTER")
        assertThat(engine.structuredRequests).hasSize(1)
        assertThat(engine.loads).isEmpty()
    }

    @Test
    @DisplayName("the answer is constrained to the candidates' ids, the made-up candidate and none")
    fun `schema is the options`() = runTest {
        theReaderReads()
        answers("""{"answer":"none"}""")

        gemma.sameContact("d1", contactQuestion)

        val schema = engine.followUpRequests.single().schema
        assertThat(schema).contains("\"enum\":[\"C1\",\"C2\",\"Z\",\"none\"]")
    }

    @Test
    @DisplayName("choosing the made-up candidate or none is no match")
    fun `made-up candidate means none`() = runTest {
        theReaderReads()

        answers("""{"answer":"Z"}""")
        val decoy = (gemma.sameContact("d1", contactQuestion) as PamResult.Success).data
        answers("""{"answer":"none"}""")
        val none = (gemma.sameContact("d1", contactQuestion) as PamResult.Success).data

        assertThat(decoy.matchedId).isNull()
        assertThat(decoy.isNewPerson).isTrue()
        assertThat(none.matchedId).isNull()
    }

    @Test
    @DisplayName("a multiple choice that includes the made-up candidate names nobody")
    fun `made-up candidate in the people check names nobody`() = runTest {
        theReaderReads()
        val maria = SubjectCandidate("maria", "Maria Mustermann")

        answers("""{"answers":["P1","Z"]}""")
        val guessing = (gemma.concernedPeople("d1", letter, listOf(maria)) as PamResult.Success).data
        answers("""{"answers":["P1"]}""")
        val sure = (gemma.concernedPeople("d1", letter, listOf(maria)) as PamResult.Success).data
        answers("""{"answers":["none"]}""")
        val nobody = (gemma.concernedPeople("d1", letter, listOf(maria)) as PamResult.Success).data

        assertThat(guessing).isEmpty()
        assertThat(sure).containsExactly("maria")
        assertThat(nobody).isEmpty()
    }

    @Test
    @DisplayName("without the reader's conversation a fresh one opens with the letter's text only, at background priority, and the next questions continue in it")
    fun `fallback conversation`() = runTest {
        // The process restarted since the reading: no conversation is open.
        engine.structuredResponder = { """{"answer":"C1"}""" }
        answers("""{"answer":"C2"}""")

        val first = (gemma.sameContact("d1", contactQuestion) as PamResult.Success).data
        val second = (gemma.sameContact("d1", contactQuestion) as PamResult.Success).data

        assertThat(first.matchedId).isEqualTo("nadine")
        val opened = engine.structuredRequests.single()
        assertThat(opened.imagePaths).isEmpty()
        assertThat(opened.keepOpenAs).isEqualTo("d1")
        assertThat(opened.prompt).startsWith("LETTER\n")
        assertThat(opened.prompt).contains("Sehr geehrte Frau Mustermann")
        assertThat(engine.loads).hasSize(1)
        // The second question went on in that conversation, which holds the letter now.
        assertThat(second.matchedId).isEqualTo("schmidt")
        assertThat(engine.structuredRequests).hasSize(1)
    }

    @Test
    @DisplayName("the people check of a letter whose conversation is gone (the backfill) opens a fresh one with the passed text")
    fun `people check backfill`() = runTest {
        engine.structuredResponder = { """{"answers":["P1"]}""" }

        val result = gemma.concernedPeople("d1", "Rechnung fuer Maria Mustermann", listOf(SubjectCandidate("maria", "Maria Mustermann")))

        assertThat((result as PamResult.Success).data).containsExactly("maria")
        assertThat(engine.structuredRequests.single().prompt).contains("Rechnung fuer Maria Mustermann")
    }

    @Test
    @DisplayName("the fallback never queues behind another caller: a busy model leaves the question pending")
    fun `busy is pending`() = runTest {
        engine.structuredResponder = { null }

        val result = gemma.sameContact("d1", contactQuestion)

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
    }

    @Test
    @DisplayName("no stored text and no conversation is pending, not 'no contact'")
    fun `no text is pending`() = runTest {
        documents.seed(testDocument(id = "empty"))

        val result = gemma.sameContact("empty", contactQuestion)

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        assertThat(engine.structuredRequests).isEmpty()
    }

    @Test
    @DisplayName("an answer outside the offered options is unusable: pending, not 'no match'")
    fun `unusable answer is pending`() = runTest {
        theReaderReads()
        answers("""{"answer":"C9"}""")

        assertThat(gemma.sameContact("d1", contactQuestion)).isInstanceOf(PamResult.Error::class.java)
    }

    @Test
    @DisplayName("a chat model that is not a LiteRT-LM one cannot be asked: pending")
    fun `other runtime is pending`() = runTest {
        provider.runtime = ModelRuntime.LLAMA_CPP

        assertThat(gemma.sameContact("d1", contactQuestion)).isInstanceOf(PamResult.Error::class.java)
        assertThat(engine.loads).isEmpty()
    }

    @Test
    @DisplayName("whose a phone number is: the organisation's, the contact person's or neither")
    fun `detail owner`() = runTest {
        theReaderReads()
        val phone = DetailQuestion(DetailKind.PHONE, "0800 555 0123", "Jobcenter Musterstadt", "Frau Nadine Beispiel", null)

        answers("""{"answer":"C"}""")
        val contact = (gemma.detailOwner("d1", phone) as PamResult.Success).data
        answers("""{"answer":"O"}""")
        val organisation = (gemma.detailOwner("d1", phone) as PamResult.Success).data
        answers("""{"answer":"Z"}""")
        val neither = (gemma.detailOwner("d1", phone) as PamResult.Success).data

        assertThat(contact).isEqualTo(DetailOwner.CONTACT)
        assertThat(organisation).isEqualTo(DetailOwner.ORGANISATION)
        assertThat(neither).isEqualTo(DetailOwner.NEITHER)
    }

    @Test
    @DisplayName("a website can only be the organisation's: the contact is not offered")
    fun `website has no contact option`() = runTest {
        theReaderReads()
        answers("""{"answer":"O"}""")

        gemma.detailOwner("d1", DetailQuestion(DetailKind.WEBSITE, "www.jobcenter-musterstadt.example", "Jobcenter Musterstadt", "Frau Nadine Beispiel", null))

        assertThat(engine.followUpRequests.single().schema).contains("\"enum\":[\"O\",\"Z\",\"none\"]")
    }

    @Test
    @DisplayName("the same-matter question chooses a matter or none")
    fun `same matter`() = runTest {
        theReaderReads()
        val question = SameMatterQuestion(
            "Jobcenter Musterstadt", NewMatterEvent("Documents requested", 1_000, "Unterlagen nachreichen"),
            listOf(MatterCandidate("m-antrag", "Antrag auf Bürgergeld", listOf("Application filed 2026-09-01: Antrag")), MatterCandidate("m-other", "Umzug")),
        )

        answers("""{"answer":"M1"}""")
        val same = (gemma.sameMatter("d1", question) as PamResult.Success).data
        answers("""{"answer":"none"}""")
        val new = (gemma.sameMatter("d1", question) as PamResult.Success).data

        assertThat(same.matchedId).isEqualTo("m-antrag")
        assertThat(new.matchedId).isNull()
    }

    @Test
    @DisplayName("finishing closes the kept conversation")
    fun `finish closes`() = runTest {
        theReaderReads()

        gemma.finish("d1")

        assertThat(engine.closedKeys).containsExactly("d1")
        assertThat(engine.keptOpenKey).isNull()
    }

    // ── the routing: the reader that read the letter answers ──

    private class CountingSameContact : SameContact {
        var asked = 0
        override suspend fun score(question: SameContactQuestion): PamResult<BaselineScores> {
            asked++
            return PamResult.Success(BaselineScores(question.candidates.map { 5.0 }, 0.0))
        }
    }

    private class RecordingLog : AfterReadingLog {
        val lines = mutableListOf<String>()
        override fun pending(documentId: String, question: String, reason: String) {
            lines += "$documentId/$question"
        }
    }

    private val qwenScores = CountingSameContact()
    private val log = RecordingLog()

    private fun routed(trial: FakeGemmaReaderTrial = FakeGemmaReaderTrial()) = RoutingFollowUpQuestions(
        trial, provider, Provider { gemma }, Provider { scoringFollowUps(sameContact = qwenScores) }, log,
    )

    @Test
    @DisplayName("with Gemma as the reader no Qwen prompt session is opened: the scorer is never asked")
    fun `gemma default never scores`() = runTest {
        theReaderReads()
        answers("""{"answer":"C1"}""")

        val decision = (routed().sameContact("d1", contactQuestion) as PamResult.Success).data

        assertThat(decision.matchedId).isEqualTo("nadine")
        assertThat(qwenScores.asked).isEqualTo(0)
    }

    @Test
    @DisplayName("with the old reader chosen the Qwen scorer answers and the engine is untouched")
    fun `old reader scores`() = runTest {
        val decision = (routed(FakeGemmaReaderTrial(enabled = false)).sameContact("d1", contactQuestion) as PamResult.Success).data

        assertThat(qwenScores.asked).isEqualTo(1)
        assertThat(decision.matchedId).isNotNull()
        assertThat(engine.followUpRequests).isEmpty()
        assertThat(engine.structuredRequests).isEmpty()
    }

    @Test
    @DisplayName("a chat model that is not Gemma's runtime is answered by the scorer")
    fun `other model scores`() = runTest {
        provider.runtime = ModelRuntime.LLAMA_CPP

        routed().sameContact("d1", contactQuestion)

        assertThat(qwenScores.asked).isEqualTo(1)
    }

    @Test
    @DisplayName("an unanswered question is reported once with the AfterReading log and is not turned into the scorer's answer")
    fun `failure is logged and pending`() = runTest {
        engine.structuredResponder = { null }

        val result = routed().sameContact("d1", contactQuestion)

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        assertThat(log.lines).containsExactly("d1/same contact")
        assertThat(qwenScores.asked).isEqualTo(0)
    }

    // ── the Jobcenter letter through the real use cases ──

    private fun managed(id: String, name: String) =
        Profile(id = id, kind = ProfileType.FAMILY_MEMBER.kind, householdRole = ProfileType.FAMILY_MEMBER.householdRole, name = name, createdAt = 0L, modifiedAt = 0L)

    private fun contactField(name: String) = ExtractedData(
        id = "c-d1", documentId = "d1", fieldName = UnderstandingToFields.CONTACT_PERSON, fieldValue = name,
        fieldType = ExtractedFieldType.PERSON_NAME, confidence = 0.9f, slotKey = UnderstandingToFields.SLOT_CONTACT,
    )

    private fun found(kind: CandidateKind, n: Int, value: String) = ExtractedData(
        id = "found-$kind-$n", documentId = "d1", fieldName = ExtractionV2Adapter.foundKey(kind, n), fieldValue = value,
        fieldType = ExtractedFieldType.OTHER, confidence = 0.3f, slotKey = ExtractionV2Adapter.foundKey(kind, n),
        origin = ExtractionV2Adapter.FOUND_ORIGIN,
    )

    private suspend fun jobcenterIsTheSender() {
        profiles.seed(testProfile(id = "jc", name = "Jobcenter Musterstadt", organization = "Jobcenter Musterstadt", kind = ProfileKind.ORGANISATION))
        profiles.linkProfileToDocument("jc", "d1", ProfileRole.SENDER)
    }

    @Test
    @DisplayName("Jobcenter: Nadine Beispiel is matched to the contact the organisation has, in the reader's conversation")
    fun `nadine is matched`() = runTest {
        theReaderReads()
        jobcenterIsTheSender()
        documents.seedExtracted("d1", contactField("Nadine Beispiel"))
        contacts.seed(ContactPerson("nadine", "jc", "Frau Nadine Beispiel", firstSeen = 1, lastSeen = 1))
        answers("""{"answer":"C1"}""")
        val link = LinkSenderContactUseCase(documents, profiles, contacts, DecideSameContactUseCase(gemma, SameContactProfile()))

        val outcome = link("d1")

        assertThat(outcome).isInstanceOf(ContactLinkOutcome.Matched::class.java)
        assertThat((outcome as ContactLinkOutcome.Matched).contactId).isEqualTo("nadine")
        assertThat(contacts.observeContacts("jc").first()).hasSize(1)
    }

    @Test
    @DisplayName("Jobcenter: a first Nadine Beispiel is created without any question; one the model does not recognise is created too")
    fun `nadine is created`() = runTest {
        theReaderReads()
        jobcenterIsTheSender()
        documents.seedExtracted("d1", contactField("Nadine Beispiel"))
        val link = LinkSenderContactUseCase(documents, profiles, contacts, DecideSameContactUseCase(gemma, SameContactProfile()))

        val first = link("d1")

        assertThat(first).isInstanceOf(ContactLinkOutcome.Created::class.java)
        assertThat(engine.followUpRequests).isEmpty()
    }

    @Test
    @DisplayName("Jobcenter: when the question cannot be answered the contact waits on the letter, it is not created as a new person")
    fun `nadine is pending`() = runTest {
        jobcenterIsTheSender()
        documents.seedExtracted("d1", contactField("Nadine Beispiel"))
        contacts.seed(ContactPerson("nadine", "jc", "Frau Nadine Beispiel", firstSeen = 1, lastSeen = 1))
        engine.structuredResponder = { null }
        val link = LinkSenderContactUseCase(documents, profiles, contacts, DecideSameContactUseCase(gemma, SameContactProfile()))

        val outcome = link("d1")

        assertThat(outcome).isEqualTo(ContactLinkOutcome.Pending(com.postsaimanager.core.domain.contacts.PendingReason.DECISION_FAILED))
        assertThat(contacts.observeContacts("jc").first()).hasSize(1)
    }

    @Test
    @DisplayName("Jobcenter: the switchboard number is the organisation's, the signature's direct number fills the contact")
    fun `the phone owner is decided`() = runTest {
        theReaderReads()
        jobcenterIsTheSender()
        documents.seedExtracted("d1", found(CandidateKind.PHONE, 1, "0800 555 0199"), found(CandidateKind.PHONE, 2, "0800 555 0123"))
        contacts.seed(ContactPerson("nadine", "jc", "Frau Nadine Beispiel", firstSeen = 1, lastSeen = 1))
        contacts.linkContactToDocument("nadine", "d1")
        engine.followUpResponder = { request -> if (request.prompt.contains("0800 555 0199")) """{"answer":"O"}""" else """{"answer":"C"}""" }
        val suggest = SuggestOrganisationDetailsUseCase(
            documents, profiles, contacts, suggestions, DecideDetailOwnerUseCase(gemma),
        )

        val outcome = suggest("d1")

        assertThat(outcome.skipped).isFalse()
        assertThat(suggestions.observePending("jc").first().filter { it.field == SuggestionField.PHONE }.map { it.value }).containsExactly("0800 555 0199")
        assertThat(contacts.getContact("nadine").let { (it as PamResult.Success).data.phone }).isEqualTo("0800 555 0123")
    }

    @Test
    @DisplayName("Jobcenter: the people check names Maria, whose name the letter has, in the reader's conversation; the stored decision is hers")
    fun `maria is concerned`() = runTest {
        theReaderReads()
        profiles.seed(managed("maria", "Maria Mustermann"), managed("stranger", "Hans Meier"))
        answers("""{"answers":["P1"]}""")
        val decide = DecideConcernedPeopleUseCase(profiles, documents, gemma)

        val result = decide("d1", letter)

        assertThat((result as PamResult.Success).data).containsExactly("maria")
        assertThat((documents.getDocumentById("d1") as PamResult.Success).data.concernedProfileIds).containsExactly("maria")
        // Only the person the letter names was offered.
        assertThat(engine.followUpRequests.single().prompt).contains("Maria Mustermann")
        assertThat(engine.followUpRequests.single().prompt).doesNotContain("Hans Meier")
    }

    @Test
    @DisplayName("Jobcenter: a people check that cannot be answered leaves the document 'not asked yet', not 'nobody'")
    fun `people check stays pending`() = runTest {
        profiles.seed(managed("maria", "Maria Mustermann"))
        engine.structuredResponder = { null }
        val decide = DecideConcernedPeopleUseCase(profiles, documents, gemma)

        val result = decide("d1", letter)

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        assertThat((documents.getDocumentById("d1") as PamResult.Success).data.concernedProfileIds).isNull()
    }
}
