package com.postsaimanager.core.domain.document.people

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.testing.FakeProfileRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DecideConcernedPeopleUseCaseTest {

    private class FakeConcerned(var answer: PamResult<Set<String>> = PamResult.Success(emptySet())) : ConcernedPeople {
        val asked = mutableListOf<List<SubjectCandidate>>()

        override suspend fun decide(letter: String, members: List<SubjectCandidate>): PamResult<Set<String>> {
            asked += members
            return answer
        }
    }

    private val profiles = FakeProfileRepository()
    private val concerned = FakeConcerned()
    private val decide = DecideConcernedPeopleUseCase(profiles, concerned)

    private fun profile(id: String, name: String, type: ProfileType = ProfileType.FAMILY_MEMBER) =
        Profile(id = id, type = type, name = name, createdAt = 0L, modifiedAt = 0L)

    private suspend fun stored(documentId: String = "d1") =
        profiles.observeDocumentLinks().first().filter { it.documentId == documentId && it.role == ProfileRole.CONCERNS }.map { it.profileId }

    @Test
    fun `only the managed people whose name token is in the letter are asked about`() = runTest {
        profiles.seed(
            profile("me", "Erika Mustermann", ProfileType.USER_SELF),
            profile("maria", "Maria Ahmed"),
            profile("stranger", "Zoltan Quill"),
            profile("authority", "Stadtwerke Musterstadt", ProfileType.AUTHORITY),
        )
        concerned.answer = PamResult.Success(setOf("maria", "stranger", "authority"))
        val result = decide("d1", "Sehr geehrte Frau Mustermann, die Rechnung fuer Maria")
        assertThat(concerned.asked.single().map { it.profileId }).containsExactly("me", "maria")
        // The model named people it was not asked about: dropped.
        assertThat((result as PamResult.Success).data).containsExactly("maria")
        assertThat(stored()).containsExactly("maria")
    }

    @Test
    fun `no name token in the letter means the model is not asked at all`() = runTest {
        profiles.seed(profile("maria", "Maria Ahmed"))
        val result = decide("d1", "Strom Rechnung Nr. 5")
        assertThat(concerned.asked).isEmpty()
        assertThat((result as PamResult.Success).data).isEmpty()
        assertThat(stored()).isEmpty()
    }

    @Test
    fun `a token is a whole word, not a part of one`() = runTest {
        profiles.seed(profile("max", "Max Beispiel"))
        decide("d1", "Maximale Ersparnis")
        assertThat(concerned.asked).isEmpty()
    }

    @Test
    fun `an earlier decision is replaced, and a person no longer named loses the chip`() = runTest {
        profiles.seed(profile("maria", "Maria Ahmed"), profile("amir", "Amir Ahmed"))
        concerned.answer = PamResult.Success(setOf("maria", "amir"))
        decide("d1", "Maria und Amir")
        assertThat(stored()).containsExactly("maria", "amir")
        concerned.answer = PamResult.Success(setOf("amir"))
        decide("d1", "Maria und Amir")
        assertThat(stored()).containsExactly("amir")
    }

    @Test
    fun `an entity linker's link of another kind is not touched by a no`() = runTest {
        profiles.seed(profile("maria", "Maria Ahmed"))
        profiles.linkProfileToDocument("maria", "d1", ProfileRole.RECEIVER)
        concerned.answer = PamResult.Success(emptySet())
        decide("d1", "Maria Ahmed")
        assertThat(profiles.observeDocumentLinks().first().map { it.role }).containsExactly(ProfileRole.RECEIVER)
    }

    @Test
    fun `no model leaves the stored decision as it was and stores nothing new`() = runTest {
        profiles.seed(profile("maria", "Maria Ahmed"))
        concerned.answer = PamResult.Success(setOf("maria"))
        decide("d1", "Maria")
        concerned.answer = PamResult.Error(PamError.InferenceError("no model"))
        val result = decide("d1", "Maria")
        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        assertThat(stored()).containsExactly("maria")
    }

    @Test
    fun `deleting a profile removes its chip`() = runTest {
        profiles.seed(profile("maria", "Maria Ahmed"))
        concerned.answer = PamResult.Success(setOf("maria"))
        decide("d1", "Maria")
        profiles.deleteProfile("maria")
        assertThat(stored()).isEmpty()
    }

    @Test
    fun `the re-check queues only the documents whose text mentions the new person`() = runTest {
        val documents = mockk<DocumentRepository>()
        val processor = mockk<DocumentProcessor>(relaxed = true)
        coEvery { documents.getOcrTexts() } returns mapOf("a" to "Rechnung fuer Maria Ahmed", "b" to "Strom Rechnung", "c" to "an Herrn Ahmed")
        val queued = QueueConcernedPeopleCheckUseCase(documents, processor)(profile("maria", "Maria Ahmed"))
        assertThat(queued).isEqualTo(2)
        coVerify(exactly = 1) { processor.enqueuePeopleCheck("a") }
        coVerify(exactly = 1) { processor.enqueuePeopleCheck("c") }
        coVerify(exactly = 0) { processor.enqueuePeopleCheck("b") }
    }

    @Test
    fun `an organisation profile queues nothing`() = runTest {
        val documents = mockk<DocumentRepository>()
        val processor = mockk<DocumentProcessor>(relaxed = true)
        coEvery { documents.getOcrTexts() } returns mapOf("a" to "Stadtwerke Musterstadt")
        assertThat(QueueConcernedPeopleCheckUseCase(documents, processor)(profile("sw", "Stadtwerke Musterstadt", ProfileType.AUTHORITY))).isEqualTo(0)
    }
}
