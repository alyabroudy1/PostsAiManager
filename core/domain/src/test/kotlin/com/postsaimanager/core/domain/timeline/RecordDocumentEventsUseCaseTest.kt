package com.postsaimanager.core.domain.timeline

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.BaselineScores
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.EventReading
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.ProfileEvent
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEventRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RecordDocumentEventsUseCaseTest {

    /** Scores a candidate matter [same] (Yes by a wide margin) when its title is in the set, else clearly No; can be made to fail. */
    private class FakeSameMatter : SameMatter {
        var sameAs: Set<String> = emptySet()
        var error: PamError? = null
        val questions = mutableListOf<SameMatterQuestion>()

        override suspend fun score(question: SameMatterQuestion): PamResult<BaselineScores> {
            questions += question
            error?.let { return PamResult.Error(it) }
            return PamResult.Success(BaselineScores(question.candidates.map { if (it.title in sameAs) 4.0 else -4.0 }, 0.0))
        }
    }

    private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)
    private val documents = FakeDocumentRepository()
    private val profiles = FakeProfileRepository()
    private val contacts = FakeContactRepository()
    private val events = FakeEventRepository()
    private val same = FakeSameMatter()
    private val record = RecordDocumentEventsUseCase(
        documents, ResolveEventLinksUseCase(profiles, contacts), events,
        DecideSameMatterUseCase(same, SameMatterProfile()), RefreshCaseStatusUseCase(events), clock,
    )

    private val jobcenter = testProfile(id = "jc", name = "Jobcenter Musterstadt", organization = "Jobcenter Musterstadt", type = ProfileType.AUTHORITY)
    private val maria = testProfile(id = "maria", name = "Maria Mustermann", type = ProfileType.FAMILY_MEMBER)
    private val me = testProfile(id = "me", name = "Erika Mustermann", type = ProfileType.USER_SELF, householdRole = HouseholdRole.SELF)

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private var next = 0
    private fun field(documentId: String, slot: String, value: String, meaning: String? = null) = ExtractedData(
        id = "f${next++}", documentId = documentId, fieldName = slot, fieldValue = value, fieldType = ExtractedFieldType.TEXT, confidence = 0.9f,
        slotKey = slot, role = meaning?.let { "meaning:$it" },
    )

    /** A read letter: from the Jobcenter (when [sender]), for [people], with its stored values. */
    private suspend fun letter(id: String, vararg fields: ExtractedData, people: List<String>? = listOf("maria"), sender: Boolean = true, title: String = "Letter $id") {
        documents.seed(testDocument(id = id, title = title, createdAt = day(2026, 10, 1)).copy(concernedProfileIds = people))
        documents.seedExtracted(id, *fields)
        if (sender) {
            if (profiles.getProfileById("jc") is PamResult.Error) profiles.seed(jobcenter)
            profiles.linkProfileToDocument("jc", id, ProfileRole.SENDER)
        }
    }

    // ── what is written ──

    @Test
    fun `one event per letter with its kind, the date of the kind's meaning, the grounded title and every link`() = runTest {
        profiles.seed(maria, me)
        contacts.seed(com.postsaimanager.core.model.ContactPerson("c1", "jc", "Nadine Beispiel", firstSeen = 1, lastSeen = 1))
        letter(
            "d1", field("d1", "letter_date", "02.09.2026", "LETTER_DATE"), field("d1", "period_start", "01.09.2026", "PERIOD_START"),
            people = listOf("maria", "me"),
        )
        contacts.linkContactToDocument("c1", "d1")

        record("d1", EventReading(EventKinds.APPROVAL, "Bürgergeld bewilligt ab 01.09.2026"))

        val event = events.allEvents.single()
        assertThat(event.kind).isEqualTo(EventKinds.APPROVAL)
        assertThat(event.title).isEqualTo("Bürgergeld bewilligt ab 01.09.2026")
        assertThat(event.eventDate).isEqualTo(day(2026, 9, 1))
        assertThat(event.recordedAt).isEqualTo(clock.millis())
        assertThat(event.source).isEqualTo(EventSource.DOCUMENT)
        assertThat(event.personProfileIds).containsExactly("maria", "me")
        assertThat(event.organisationProfileId).isEqualTo("jc")
        assertThat(event.contactId).isEqualTo("c1")
        assertThat(event.caseId).isNotNull()
    }

    @Test
    fun `with no grounded title the document's own title stands in, and an unknown kind is information`() = runTest {
        letter("d1", title = "Jobcenter · Bescheid")
        record("d1", EventReading("from-a-newer-build", null))
        val event = events.allEvents.single()
        assertThat(event.title).isEqualTo("Jobcenter · Bescheid")
        assertThat(event.kind).isEqualTo(EventKinds.INFORMATION)
    }

    @Test
    fun `no sender organisation means no matter, and no people means no person links`() = runTest {
        letter("d1", sender = false, people = null)
        record("d1", EventReading(EventKinds.INFORMATION, "x"))
        val event = events.allEvents.single()
        assertThat(event.caseId).isNull()
        assertThat(event.organisationProfileId).isNull()
        assertThat(event.personProfileIds).isEmpty()
        assertThat(events.allCases).isEmpty()
    }

    // ── cases by reference ──

    @Test
    fun `letters of one organisation sharing an exact reference are one matter`() = runTest {
        letter("d1", field("d1", "case_no", "BG 3141592"), field("d1", "letter_date", "01.09.2026", "LETTER_DATE"))
        letter("d2", field("d2", "case_no", "bg-3141592"), field("d2", "letter_date", "15.09.2026", "LETTER_DATE"))
        letter("d3", field("d3", "case_no", "BG 2718281"))

        record("d1", EventReading(EventKinds.APPLICATION_FILED, "Antrag gestellt"))
        record("d2", EventReading(EventKinds.APPROVAL, "Antrag bewilligt"))
        record("d3", EventReading(EventKinds.APPLICATION_FILED, "Anderer Antrag"))

        val byDocument = events.allEvents.associate { it.documentId to it.caseId }
        assertThat(byDocument.getValue("d1")).isEqualTo(byDocument.getValue("d2"))
        assertThat(byDocument.getValue("d3")).isNotEqualTo(byDocument.getValue("d1"))
        assertThat(events.allCases).hasSize(2)
        // No reference in common, so the model was never asked about d2; for d3 it was (and said no).
        assertThat(same.questions).hasSize(1)
        val matter = events.getCase(byDocument.getValue("d1")!!)!!
        assertThat(matter.title).isEqualTo("Antrag gestellt")
        assertThat(matter.referenceKeys).containsExactly("BG3141592")
        assertThat(matter.status).isEqualTo(CaseStatus.APPROVED)
    }

    @Test
    fun `a shared reference is not enough across organisations`() = runTest {
        letter("d1", field("d1", "case_no", "BG 3141592"))
        letter("d2", field("d2", "case_no", "BG 3141592"), sender = false)
        profiles.seed(testProfile(id = "other", name = "Finanzamt", type = ProfileType.AUTHORITY))
        profiles.linkProfileToDocument("other", "d2", ProfileRole.SENDER)

        record("d1", EventReading(EventKinds.INFORMATION, "a"))
        record("d2", EventReading(EventKinds.INFORMATION, "b"))

        assertThat(events.allCases).hasSize(2)
    }

    @Test
    fun `a matter learns the new reference keys of a letter that joined it`() = runTest {
        letter("d1", field("d1", "case_no", "BG 3141592"))
        letter("d2", field("d2", "case_no", "BG 3141592"), field("d2", "customer_no", "K-998877"))
        record("d1", EventReading(EventKinds.INFORMATION, "a"))
        record("d2", EventReading(EventKinds.INFORMATION, "b"))
        assertThat(events.allCases.single().referenceKeys).containsExactly("BG3141592", "K998877")
    }

    // ── cases by the model's same-matter question ──

    @Test
    fun `without a shared reference the model's same-matter question decides, over the matter's title and latest events`() = runTest {
        letter("d1", field("d1", "case_no", "BG 3141592"))
        record("d1", EventReading(EventKinds.APPLICATION_FILED, "Antrag auf Bürgergeld"))
        letter("d2")
        same.sameAs = setOf("Antrag auf Bürgergeld")

        record("d2", EventReading(EventKinds.DOCUMENTS_REQUESTED, "Unterlagen nachreichen"))

        assertThat(events.allCases).hasSize(1)
        assertThat(events.allEvents.map { it.caseId }.distinct()).hasSize(1)
        val question = same.questions.single()
        assertThat(question.organisation).isEqualTo("Jobcenter Musterstadt")
        assertThat(question.event.kindLabel).isEqualTo("Documents requested")
        assertThat(question.candidates.single().latestEvents.single()).contains("Antrag auf Bürgergeld")
        assertThat(question.candidates.single().latestEvents.single()).startsWith("Application filed ")
    }

    @Test
    fun `a matter the model does not pick, or no model, gives a new matter`() = runTest {
        letter("d1")
        record("d1", EventReading(EventKinds.APPLICATION_FILED, "Antrag auf Bürgergeld"))
        letter("d2")
        record("d2", EventReading(EventKinds.PAYMENT_DEMAND, "Rechnung Mai"))
        letter("d3")
        same.error = PamError.InferenceError("no model")
        record("d3", EventReading(EventKinds.INFORMATION, "Hinweis"))

        assertThat(events.allCases).hasSize(3)
    }

    @Test
    fun `a matter reference beats the model, which is not asked`() = runTest {
        letter("d1", field("d1", "case_no", "BG 3141592"))
        record("d1", EventReading(EventKinds.INFORMATION, "a"))
        letter("d2", field("d2", "case_no", "BG 3141592"))
        record("d2", EventReading(EventKinds.INFORMATION, "b"))
        assertThat(same.questions).isEmpty()
    }

    // ── re-reading ──

    @Test
    fun `reading a letter again replaces its DOCUMENT event and keeps the USER, ACTION and SYSTEM ones`() = runTest {
        letter("d1", field("d1", "case_no", "BG 3141592"))
        record("d1", EventReading(EventKinds.PAYMENT_DEMAND, "old"))
        val matter = events.allEvents.single().caseId
        val kept = listOf(EventSource.USER, EventSource.ACTION, EventSource.SYSTEM).map {
            ProfileEvent("k-$it", "d1", EventKinds.INFORMATION, 1, 1, "kept $it", caseId = matter, source = it)
        }
        kept.forEach { events.addEvent(it) }

        record("d1", EventReading(EventKinds.REJECTION, "new"))

        val after = events.allEvents
        assertThat(after.filter { it.source == EventSource.DOCUMENT }.map { it.title }).containsExactly("new")
        assertThat(after.filter { it.source != EventSource.DOCUMENT }).containsExactlyElementsIn(kept)
        // The same matter, now rejected: the letter was not shuffled into another one or a second one.
        assertThat(events.allCases).hasSize(1)
        assertThat(events.allCases.single().id).isEqualTo(matter)
        assertThat(events.allCases.single().status).isEqualTo(CaseStatus.REJECTED)
    }

    @Test
    fun `a re-read letter stays in its matter without asking the model again`() = runTest {
        letter("d1")
        record("d1", EventReading(EventKinds.APPLICATION_FILED, "Antrag"))
        letter("d2")
        same.sameAs = setOf("Antrag")
        record("d2", EventReading(EventKinds.INFORMATION, "Hinweis"))
        same.questions.clear()
        same.sameAs = emptySet()

        record("d2", EventReading(EventKinds.DOCUMENTS_REQUESTED, "Unterlagen"))

        assertThat(same.questions).isEmpty()
        assertThat(events.allCases).hasSize(1)
        assertThat(events.allEvents.map { it.caseId }.distinct()).hasSize(1)
    }

    @Test
    fun `a trashed or missing letter writes nothing`() = runTest {
        documents.seed(testDocument(id = "gone", deletedAt = 5))
        record("gone", EventReading(EventKinds.INFORMATION, "x"))
        record("never-seen", EventReading(EventKinds.INFORMATION, "x"))
        assertThat(events.allEvents).isEmpty()
    }

    // ── links decided later ──

    @Test
    fun `the people decided after the events were written are linked by the sync, and an empty matter is not left behind`() = runTest {
        profiles.seed(maria)
        letter("d1", people = null)
        record("d1", EventReading(EventKinds.INFORMATION, "x"))
        assertThat(events.allEvents.single().personProfileIds).isEmpty()

        documents.setConcernedProfiles("d1", listOf("maria"))
        SyncEventLinksUseCase(documents, ResolveEventLinksUseCase(profiles, contacts), events)("d1")

        assertThat(events.allEvents.single().personProfileIds).containsExactly("maria")
    }

    @Test
    fun `renaming a matter keeps it renamed, and a blank name is ignored`() = runTest {
        letter("d1")
        record("d1", EventReading(EventKinds.APPLICATION_FILED, "Antrag"))
        val id = events.allCases.single().id
        RenameCaseUseCase(events)(id, "  Bürgergeld 2026 ")
        RenameCaseUseCase(events)(id, "   ")
        assertThat(events.getCase(id)!!.title).isEqualTo("Bürgergeld 2026")
        assertThat(events.allCases.single()).isInstanceOf(Case::class.java)
    }
}
