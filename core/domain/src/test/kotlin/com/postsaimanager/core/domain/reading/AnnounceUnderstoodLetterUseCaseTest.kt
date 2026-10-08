package com.postsaimanager.core.domain.reading

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.DocumentTitleCodes
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.testing.FakeUserPreferencesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * When a "Letter understood" notification is posted and what it may say: only when the switch is on and the person is not already
 * looking at that letter; nothing of a sensitive letter; one grouped notification for letters finishing together.
 */
class AnnounceUnderstoodLetterUseCaseTest {

    private val preferences = FakeUserPreferencesRepository()
    private val viewing = ViewingState()
    private val batch = ReadingFinishedBatch(10 * 60 * 1000L)
    private var showing = false
    private val shown = mutableListOf<ReadingFinishedContent>()
    private var canPost = true
    private val notifier = object : ReadingFinishedNotifier {
        override fun isShowing() = showing
        override fun show(content: ReadingFinishedContent): Boolean {
            if (!canPost) return false
            shown += content
            showing = true
            return true
        }
    }
    private var now = 0L
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val documents = mutableMapOf<String, Document>()
    private val documentRepository = mockk<DocumentRepository> {
        coEvery { getDocumentById(any()) } answers {
            documents[firstArg()]?.let { PamResult.Success(it) } ?: PamResult.Error(PamError.FileNotFound(path = firstArg()))
        }
        every { observeExtractedData(any()) } returns flowOf(
            listOf(
                ExtractedData(id = "t", documentId = "x", fieldName = "t", fieldValue = "104,20 €", fieldType = ExtractedFieldType.TEXT, confidence = 0.9f, slotKey = "total"),
                ExtractedData(id = "d", documentId = "x", fieldName = "d", fieldValue = "15.10.2026", fieldType = ExtractedFieldType.TEXT, confidence = 0.9f, slotKey = "due_date"),
            ),
        )
    }
    private var people = emptyList<Profile>()
    private val profileRepository = mockk<ProfileRepository> { every { getProfiles() } answers { flowOf(people) } }

    private val useCase = AnnounceUnderstoodLetterUseCase(preferences, documentRepository, profileRepository, viewing, batch, notifier, clock)

    private fun letter(id: String, type: String = "invoice_bill", concerned: List<String>? = emptyList()) {
        documents[id] = Document(
            id = id, title = "Rechnung", status = DocumentStatus.EXTRACTED, sourceType = SourceType.CAMERA, createdAt = 0L, modifiedAt = 0L,
            extractionType = type, concernedProfileIds = concerned,
            actionItems = listOf(ActionItem("pay", mapOf("amount" to "total", "date" to "due_date"))),
            titleCode = DocumentTitleCodes.COMPOSED, titleArgs = listOf(type, "Stadtwerke", "Rechnung Juli"),
        )
    }

    // ── when ──

    @Test
    fun `a letter understood in the background is announced`() = runTest {
        letter("a")
        assertThat(useCase("a")).isTrue()
        assertThat(shown).hasSize(1)
        assertThat(shown.single().documentId).isEqualTo("a")
        assertThat(shown.single().highlight?.amount).isEqualTo("104,20 €")
    }

    @Test
    fun `nothing is posted while the person is looking at that letter`() = runTest {
        letter("a"); letter("b")
        viewing.setAppInForeground(true)
        viewing.documentOpened("a")

        assertThat(useCase("a")).isFalse()
        assertThat(shown).isEmpty()
        // Another letter is announced even while the app is in front: they are not looking at it.
        assertThat(useCase("b")).isTrue()
    }

    @Test
    fun `a letter is announced when the app is in the background even if its screen was the last one shown`() = runTest {
        letter("a")
        viewing.documentOpened("a")
        viewing.setAppInForeground(false)
        assertThat(useCase("a")).isTrue()
    }

    @Test
    fun `the Reading finished switch off posts nothing`() = runTest {
        letter("a")
        preferences.setReadingFinishedNotifications(false)
        assertThat(useCase("a")).isFalse()
        assertThat(shown).isEmpty()
    }

    @Test
    fun `the switch is on by default and separate from the deadline reminders`() = runTest {
        assertThat(preferences.current.readingFinishedNotifications).isTrue()
        preferences.setNotificationsEnabled(false)
        assertThat(preferences.current.readingFinishedNotifications).isTrue()
        preferences.setReadingFinishedNotifications(false)
        assertThat(preferences.current.notificationsEnabled).isFalse()
        preferences.setNotificationsEnabled(true)
        assertThat(preferences.current.readingFinishedNotifications).isFalse()
    }

    @Test
    fun `without the notification permission nothing is posted and no error follows`() = runTest {
        letter("a")
        canPost = false
        assertThat(useCase("a")).isFalse()
        assertThat(shown).isEmpty()
    }

    @Test
    fun `a missing or trashed letter is not announced`() = runTest {
        assertThat(useCase("missing")).isFalse()
        letter("a")
        documents["a"] = documents.getValue("a").copy(deletedAt = 1L)
        assertThat(useCase("a")).isFalse()
        assertThat(shown).isEmpty()
    }

    // ── what ──

    @Test
    fun `a health letter is announced without any of its content`() = runTest {
        letter("a", type = "medical")
        useCase("a")
        val content = shown.single()
        assertThat(content.documentId).isEqualTo("a")
        assertThat(content.title).isNull()
        assertThat(content.highlight).isNull()
    }

    @Test
    fun `a letter for a sensitive person is announced without any of its content`() = runTest {
        people = listOf(Profile(id = "maria", householdRole = HouseholdRole.MEMBER, name = "Maria", sensitive = true, createdAt = 0L, modifiedAt = 0L))
        letter("a", concerned = listOf("maria"))
        useCase("a")
        assertThat(shown.single().title).isNull()
        assertThat(shown.single().highlight).isNull()
    }

    @Test
    fun `with the app lock on the notification carries no content`() = runTest {
        letter("a")
        preferences.setBiometricEnabled(true)
        useCase("a")
        assertThat(shown.single().title).isNull()
        assertThat(shown.single().highlight).isNull()
    }

    // ── grouping ──

    @Test
    fun `letters finishing together become one grouped notification that keeps the privacy of each`() = runTest {
        letter("a"); letter("b"); letter("c", type = "medical")
        now = 0; useCase("a")
        now = 3 * 60_000; useCase("b")
        now = 6 * 60_000; useCase("c")

        assertThat(shown.map { it.count }).containsExactly(1, 2, 3).inOrder()
        val group = shown.last()
        assertThat(group.documentId).isNull()
        assertThat(group.lines).hasSize(2)
        assertThat(group.hiddenCount).isEqualTo(1)
    }

    @Test
    fun `after the notification was dismissed the next letter starts a new one`() = runTest {
        letter("a"); letter("b")
        useCase("a")
        showing = false
        now = 60_000
        useCase("b")
        assertThat(shown.map { it.count }).containsExactly(1, 1).inOrder()
    }
}
