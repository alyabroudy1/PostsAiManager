package com.postsaimanager.core.domain.document.people

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.InstalledModelSummary
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.scoringFollowUps
import com.postsaimanager.core.testing.testDocument
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DecideConcernedPeopleUseCaseTest {

    private class FakeConcerned(var answer: PamResult<Set<String>> = PamResult.Success(emptySet())) : ConcernedPeople {
        val asked = mutableListOf<List<SubjectCandidate>>()

        override suspend fun decide(letter: String, members: List<SubjectCandidate>): PamResult<Set<String>> {
            asked += members
            return answer
        }
    }

    private val profiles = FakeProfileRepository()
    private val documents = FakeDocumentRepository()
    private val concerned = FakeConcerned()
    private val processor = mockk<DocumentProcessor>(relaxed = true)
    private val decide = DecideConcernedPeopleUseCase(profiles, documents, scoringFollowUps(concerned = concerned))

    private fun profile(id: String, name: String, type: ProfileType = ProfileType.FAMILY_MEMBER) =
        Profile(id = id, kind = type.kind, householdRole = type.householdRole, name = name, createdAt = 0L, modifiedAt = 0L)

    private fun seedDocument(id: String, text: String, concernedIds: List<String>? = null) {
        documents.seed(testDocument(id = id, extractorVersion = "extraction-v2-10").copy(concernedProfileIds = concernedIds))
        documents.seedPages(id, DocumentPage(id = "p-$id", documentId = id, pageNumber = 1, imagePath = "/p.jpg", ocrText = text))
    }

    private suspend fun stored(id: String = "d1") = (documents.getDocumentById(id) as PamResult.Success).data.concernedProfileIds

    @Test
    fun `only the managed people whose name token is in the letter are asked about`() = runTest {
        profiles.seed(
            profile("me", "Erika Mustermann", ProfileType.USER_SELF),
            profile("maria", "Maria Ahmed"),
            profile("stranger", "Zoltan Quill"),
            profile("authority", "Stadtwerke Musterstadt", ProfileType.AUTHORITY),
        )
        seedDocument("d1", "x")
        concerned.answer = PamResult.Success(setOf("maria", "stranger", "authority"))
        val result = decide("d1", "Sehr geehrte Frau Mustermann, die Rechnung fuer Maria")
        assertThat(concerned.asked.single().map { it.profileId }).containsExactly("me", "maria")
        // The model named people it was not asked about: dropped.
        assertThat((result as PamResult.Success).data).containsExactly("maria")
        assertThat(stored()).containsExactly("maria")
    }

    @Test
    fun `no name token in the letter settles to nobody without asking the model`() = runTest {
        profiles.seed(profile("maria", "Maria Ahmed"))
        seedDocument("d1", "x")
        val result = decide("d1", "Strom Rechnung Nr. 5")
        assertThat(concerned.asked).isEmpty()
        assertThat((result as PamResult.Success).data).isEmpty()
        assertThat(stored()).isEmpty()
    }

    @Test
    fun `a token is a whole word, not a part of one`() = runTest {
        profiles.seed(profile("max", "Max Beispiel"))
        seedDocument("d1", "x")
        decide("d1", "Maximale Ersparnis")
        assertThat(concerned.asked).isEmpty()
    }

    @Test
    fun `an earlier decision is replaced`() = runTest {
        profiles.seed(profile("maria", "Maria Ahmed"), profile("amir", "Amir Ahmed"))
        seedDocument("d1", "x")
        concerned.answer = PamResult.Success(setOf("maria", "amir"))
        decide("d1", "Maria und Amir")
        assertThat(stored()).containsExactly("maria", "amir")
        concerned.answer = PamResult.Success(setOf("amir"))
        decide("d1", "Maria und Amir")
        assertThat(stored()).containsExactly("amir")
    }

    @Test
    fun `no model leaves the stored decision as it was`() = runTest {
        profiles.seed(profile("maria", "Maria Ahmed"))
        seedDocument("d1", "x", concernedIds = listOf("maria"))
        concerned.answer = PamResult.Error(PamError.InferenceError("no model"))
        assertThat(decide("d1", "Maria")).isInstanceOf(PamResult.Error::class.java)
        assertThat(stored()).containsExactly("maria")
    }

    @Test
    fun `a new profile resets only the documents whose text mentions it, and queues those`() = runTest {
        seedDocument("a", "Rechnung fuer Maria Ahmed", concernedIds = emptyList())
        seedDocument("b", "Strom Rechnung", concernedIds = emptyList())
        seedDocument("c", "an Herrn Ahmed", concernedIds = listOf("me"))
        val queued = QueueConcernedPeopleCheckUseCase(documents, processor)(profile("maria", "Maria Ahmed"))
        assertThat(queued).isEqualTo(2)
        assertThat(stored("a")).isNull()
        assertThat(stored("c")).isNull()
        assertThat(stored("b")).isEmpty()
        coVerify(exactly = 1) { processor.enqueuePeopleCheck("a") }
        coVerify(exactly = 1) { processor.enqueuePeopleCheck("c") }
        coVerify(exactly = 0) { processor.enqueuePeopleCheck("b") }
    }

    @Test
    fun `an organisation profile queues nothing`() = runTest {
        seedDocument("a", "Stadtwerke Musterstadt")
        assertThat(QueueConcernedPeopleCheckUseCase(documents, processor)(profile("sw", "Stadtwerke Musterstadt", ProfileType.AUTHORITY))).isEqualTo(0)
    }

    @Test
    fun `the backfill queues the documents not asked yet, and only those`() = runTest {
        seedDocument("asked", "x", concernedIds = emptyList())
        seedDocument("new1", "x")
        seedDocument("new2", "x")
        assertThat(BackfillConcernedPeopleUseCase(documents, processor)()).isEqualTo(2)
        coVerify(exactly = 1) { processor.enqueuePeopleCheck("new1") }
        coVerify(exactly = 1) { processor.enqueuePeopleCheck("new2") }
        coVerify(exactly = 0) { processor.enqueuePeopleCheck("asked") }
    }

    @Test
    fun `the watcher backfills when a model appears and queues a profile added or renamed after the start, not those present at start`() =
        runTest(UnconfinedTestDispatcher()) {
            val installed = MutableStateFlow(emptyList<InstalledModelSummary>())
            val models = object : InstalledModelsRepository {
                override val installed: Flow<List<InstalledModelSummary>> = installed
                override val activeModelId: Flow<String?> = MutableStateFlow(null)
                override suspend fun setActive(modelId: String) = Unit
            }
            profiles.seed(profile("at-start", "Amir Ahmed"))
            seedDocument("d1", "Amir Ahmed und Maria Ahmed")
            val watcher = ConcernedPeopleWatcher(
                profiles, models, QueueConcernedPeopleCheckUseCase(documents, processor), BackfillConcernedPeopleUseCase(documents, processor),
            )
            val job = launch { watcher.watch() }

            // The profile present at start is the baseline: nothing is queued for it, and with no model nothing is backfilled.
            coVerify(exactly = 0) { processor.enqueuePeopleCheck(any()) }

            installed.value = listOf(InstalledModelSummary("m", "m", "/m.gguf", 1L, null, 4096))
            coVerify(exactly = 1) { processor.enqueuePeopleCheck("d1") }

            profiles.createProfile(profile("maria", "Maria Ahmed"))
            coVerify(exactly = 2) { processor.enqueuePeopleCheck("d1") }

            profiles.updateProfile(profile("maria", "Mia Ahmed"))
            // "Mia Ahmed" still shares the token "Ahmed" with the letter: queued again.
            coVerify(exactly = 3) { processor.enqueuePeopleCheck("d1") }
            job.cancel()
        }

    @Test
    fun `a person who joins the household under the same name sets the documents that mention them back to not asked`() =
        runTest(UnconfinedTestDispatcher()) {
            val models = object : InstalledModelsRepository {
                override val installed: Flow<List<InstalledModelSummary>> = MutableStateFlow(emptyList())
                override val activeModelId: Flow<String?> = MutableStateFlow(null)
                override suspend fun setActive(modelId: String) = Unit
            }
            // Read long ago and settled to "nobody", because there was no household then.
            profiles.seed(profile("erika", "Erika Mustermann", ProfileType.PERSON))
            seedDocument("d1", "Sehr geehrte Frau Erika Mustermann", concernedIds = emptyList())
            seedDocument("d2", "Ein Brief ohne Namen", concernedIds = emptyList())
            val watcher = ConcernedPeopleWatcher(
                profiles, models, QueueConcernedPeopleCheckUseCase(documents, processor), BackfillConcernedPeopleUseCase(documents, processor),
            )
            val job = launch { watcher.watch() }
            coVerify(exactly = 0) { processor.enqueuePeopleCheck(any()) }

            profiles.updateProfile(profile("erika", "Erika Mustermann", ProfileType.USER_SELF))

            coVerify(exactly = 1) { processor.enqueuePeopleCheck("d1") }
            coVerify(exactly = 0) { processor.enqueuePeopleCheck("d2") }
            assertThat(stored("d1")).isNull()
            assertThat(stored("d2")).isEmpty()
            job.cancel()
        }

    @Test
    fun `the watcher's diff names the new and the renamed managed profiles only`() {
        val now = listOf(profile("a", "Amir Ahmed"), profile("b", "Maria Ahmed"), profile("c", "Lea Ahmed"))
        val changed = ConcernedPeopleWatcher.changed(mapOf("a" to "Amir Ahmed", "b" to "Maria Berger"), now)
        assertThat(changed.map { it.id }).containsExactly("b", "c")
    }
}
