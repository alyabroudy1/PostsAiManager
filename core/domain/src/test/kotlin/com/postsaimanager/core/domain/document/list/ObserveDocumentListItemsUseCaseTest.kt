package com.postsaimanager.core.domain.document.list

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentDateChip
import com.postsaimanager.core.model.DocumentProfileLink
import com.postsaimanager.core.model.PersonRole
import com.postsaimanager.core.model.PersonTag
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.DocumentListStatus
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.ValueSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ObserveDocumentListItemsUseCaseTest {

    private val today = LocalDate.of(2026, 9, 30)
    private val clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val scanMillis = Instant.parse("2026-09-01T08:00:00Z").toEpochMilli()

    private fun doc(
        status: DocumentStatus = DocumentStatus.EXTRACTED,
        type: String? = null,
        title: String = "AI title",
        isUserTitle: Boolean = false,
        titleCode: String? = null,
        titleArgs: List<String> = emptyList(),
    ) = Document(
        id = "d1", title = title, status = status, sourceType = SourceType.CAMERA, createdAt = scanMillis,
        modifiedAt = scanMillis, extractionType = type, isUserTitle = isUserTitle, titleCode = titleCode, titleArgs = titleArgs,
    )

    private var next = 0
    private fun field(
        slot: String?,
        value: String,
        confidence: Float = 0.9f,
        role: String? = null,
        name: String = slot.orEmpty(),
        type: ExtractedFieldType = ExtractedFieldType.TEXT,
        source: ValueSource = ValueSource.MACHINE,
        confirmed: Boolean = false,
        deleted: Boolean = false,
    ) = ExtractedData(
        id = "f${next++}", documentId = "d1", fieldName = name, fieldValue = value, fieldType = type, confidence = confidence,
        slotKey = slot, role = role, source = source, isConfirmed = confirmed, deletedByUser = deleted,
    )

    private fun repository(
        document: Document,
        fields: List<ExtractedData> = emptyList(),
        firstPage: String? = null,
    ): DocumentRepository = mockk {
        every { getDocuments() } returns flowOf(listOf(document))
        every { searchDocuments(any()) } returns flowOf(listOf(document))
        every { observeListFields() } returns flowOf(if (fields.isEmpty()) emptyMap() else mapOf(document.id to fields))
        every { observeFirstPagePaths() } returns flowOf(firstPage?.let { mapOf(document.id to it) } ?: emptyMap())
    }

    private suspend fun rowOf(
        document: Document,
        fields: List<ExtractedData> = emptyList(),
        firstPage: String? = null,
        hint: ActionHint = DueFieldsActionHint(),
        names: PartyNameResolver = IdentityPartyNameResolver(),
    ) = useCase(repository(document, fields, firstPage), names, hint)().first().single()

    private fun profiles(
        profiles: Flow<List<Profile>> = flowOf(emptyList()),
        links: Flow<List<DocumentProfileLink>> = flowOf(emptyList()),
    ): ProfileRepository = mockk {
        every { getProfiles() } returns profiles
        every { observeDocumentLinks() } returns links
    }

    private fun useCase(
        repo: DocumentRepository,
        names: PartyNameResolver = IdentityPartyNameResolver(),
        hint: ActionHint = DueFieldsActionHint(),
        profileRepository: ProfileRepository = profiles(),
    ) = ObserveDocumentListItemsUseCase(repo, names, hint, clock, profileRepository, MatchDocumentPeopleUseCase())

    private fun profile(id: String, name: String, type: ProfileType = ProfileType.FAMILY_MEMBER) =
        Profile(id = id, type = type, name = name, createdAt = 0L, modifiedAt = 0L)

    // ── people ──

    @Test
    fun `the row names the managed people from the addressee and the subject person`() = runTest {
        val repo = repository(doc(), listOf(field("addressee", "Erika Mustermann"), field("subject_person", "Maria Mustermann")))
        val row = useCase(
            repo,
            profileRepository = profiles(
                flowOf(listOf(profile("me", "Erika Mustermann", ProfileType.USER_SELF), profile("maria", "Maria Mustermann"))),
            ),
        )().first().single()
        assertThat(row.people).containsExactly(
            PersonTag("me", "Erika", PersonRole.FOR, isMe = true),
            PersonTag("maria", "Maria", PersonRole.ABOUT, isMe = false),
        ).inOrder()
    }

    @Test
    fun `a stored link names the person without any printed name`() = runTest {
        val row = useCase(
            repository(doc(), listOf(field("addressee", "Familie B."))),
            profileRepository = profiles(
                flowOf(listOf(profile("maria", "Maria Mustermann"))),
                flowOf(listOf(DocumentProfileLink("d1", "maria", ProfileRole.RECEIVER), DocumentProfileLink("other", "maria", ProfileRole.SUBJECT))),
            ),
        )().first().single()
        assertThat(row.people).containsExactly(PersonTag("maria", "Maria", PersonRole.FOR, isMe = false))
    }

    @Test
    fun `the people update when a profile is added or renamed`() = runTest {
        val all = MutableStateFlow(emptyList<Profile>())
        val flow = useCase(repository(doc(), listOf(field("addressee", "Maria Mustermann"))), profileRepository = profiles(all))()
        assertThat(flow.first().single().people).isEmpty()
        all.value = listOf(profile("maria", "Maria Mustermann"))
        assertThat(flow.first().single().people).containsExactly(PersonTag("maria", "Maria", PersonRole.FOR, isMe = false))
        all.value = listOf(profile("maria", "Mia Mustermann"))
        assertThat(flow.first().single().people).isEmpty()
    }

    // ── status ──

    @Test
    fun `new and queued documents wait, processing processes, failed fails`() = runTest {
        assertThat(rowOf(doc(DocumentStatus.NEW)).status).isEqualTo(DocumentListStatus.Waiting)
        assertThat(rowOf(doc(DocumentStatus.QUEUED)).status).isEqualTo(DocumentListStatus.Waiting)
        assertThat(rowOf(doc(DocumentStatus.PROCESSING)).status).isEqualTo(DocumentListStatus.Processing)
        assertThat(rowOf(doc(DocumentStatus.FAILED)).status).isEqualTo(DocumentListStatus.Failed)
    }

    @Test
    fun `an extracted document counts the fields that need review`() = runTest {
        val fields = listOf(
            field("sender", "Stadtwerke", confidence = 0.5f),
            field("total", "12,00 EUR", confidence = 0.6f),
            field("reference", "A-1", confidence = 0.95f),
        )
        assertThat(rowOf(doc(), fields).status).isEqualTo(DocumentListStatus.NeedsReview(2))
    }

    @Test
    fun `an extracted document with nothing to check is ready`() = runTest {
        val fields = listOf(
            field("sender", "Stadtwerke", confidence = 0.95f),
            field("total", "12,00 EUR", confidence = 0.3f, source = ValueSource.USER),
            field("reference", "A-1", confidence = 0.2f, deleted = true),
        )
        assertThat(rowOf(doc(), fields).status).isEqualTo(DocumentListStatus.Ready)
    }

    @Test
    fun `a faint machine extra is hidden on the detail screen and not counted`() = runTest {
        val fields = listOf(field("x:klasse", "2a", confidence = 0.3f), field("x:raum", "5", confidence = 0.6f))
        assertThat(rowOf(doc(), fields).status).isEqualTo(DocumentListStatus.NeedsReview(1))
    }

    @Test
    fun `reviewed and archived documents are ready even with low confidence fields`() = runTest {
        val fields = listOf(field("sender", "Stadtwerke", confidence = 0.5f))
        assertThat(rowOf(doc(DocumentStatus.REVIEWED), fields).status).isEqualTo(DocumentListStatus.Ready)
        assertThat(rowOf(doc(DocumentStatus.ARCHIVED), fields).status).isEqualTo(DocumentListStatus.Ready)
    }

    // ── date chip ──

    @Test
    fun `a deadline wins over the letter date and the scan date`() = runTest {
        val fields = listOf(field("due_date", "15.10.2026"), field("letter_date", "01.09.2026"))
        val chip = rowOf(doc(), fields).dateChip
        assertThat(chip.kind).isEqualTo(DocumentDateChip.Kind.DUE)
        assertThat(chip.date).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(chip.urgency).isEqualTo(DocumentDateChip.Urgency.NORMAL)
    }

    @Test
    fun `an objection deadline counts as a deadline when there is no due date`() = runTest {
        val chip = rowOf(doc(), listOf(field("objection_deadline", "October 20, 2026"))).dateChip
        assertThat(chip.kind).isEqualTo(DocumentDateChip.Kind.DUE)
        assertThat(chip.date).isEqualTo(LocalDate.of(2026, 10, 20))
    }

    @Test
    fun `a deadline three days away is soon and today is soon too`() = runTest {
        assertThat(rowOf(doc(), listOf(field("due_date", "03.10.2026"))).dateChip.urgency)
            .isEqualTo(DocumentDateChip.Urgency.SOON)
        assertThat(rowOf(doc(), listOf(field("due_date", "30.09.2026"))).dateChip.urgency)
            .isEqualTo(DocumentDateChip.Urgency.SOON)
        assertThat(rowOf(doc(), listOf(field("due_date", "04.10.2026"))).dateChip.urgency)
            .isEqualTo(DocumentDateChip.Urgency.NORMAL)
    }

    @Test
    fun `a deadline in the past is overdue`() = runTest {
        val chip = rowOf(doc(), listOf(field("due_date", "29.09.2026"))).dateChip
        assertThat(chip.kind).isEqualTo(DocumentDateChip.Kind.DUE)
        assertThat(chip.urgency).isEqualTo(DocumentDateChip.Urgency.OVERDUE)
    }

    @Test
    fun `a deadline given in words falls back to the letter date`() = runTest {
        val fields = listOf(field("due_date", "innerhalb von 14 Tagen"), field("letter_date", "2026-09-10"))
        val chip = rowOf(doc(), fields).dateChip
        assertThat(chip.kind).isEqualTo(DocumentDateChip.Kind.LETTER)
        assertThat(chip.date).isEqualTo(LocalDate.of(2026, 9, 10))
        assertThat(chip.urgency).isEqualTo(DocumentDateChip.Urgency.NORMAL)
    }

    @Test
    fun `without any date field the chip is the scan date`() = runTest {
        val chip = rowOf(doc()).dateChip
        assertThat(chip.kind).isEqualTo(DocumentDateChip.Kind.SCANNED)
        assertThat(chip.date).isEqualTo(LocalDate.of(2026, 9, 1))
    }

    @Test
    fun `a deadline the user deleted is ignored`() = runTest {
        val chip = rowOf(doc(), listOf(field("due_date", "15.10.2026", deleted = true))).dateChip
        assertThat(chip.kind).isEqualTo(DocumentDateChip.Kind.SCANNED)
    }

    @Test
    fun `a field an older extractor named Deadline still gives the deadline`() = runTest {
        val chip = rowOf(doc(), listOf(field(null, "15.10.2026", name = "Deadline", type = ExtractedFieldType.DEADLINE))).dateChip
        assertThat(chip.kind).isEqualTo(DocumentDateChip.Kind.DUE)
    }

    // ── parties ──

    @Test
    fun `both sides come from the sender and addressee slots`() = runTest {
        val row = rowOf(doc(), listOf(field("sender", "Stadtwerke Musterstadt"), field("addressee", "Familie Beispiel")))
        assertThat(row.sender).isEqualTo("Stadtwerke Musterstadt")
        assertThat(row.addressee).isEqualTo("Familie Beispiel")
    }

    @Test
    fun `a missing side is null and the other stays`() = runTest {
        assertThat(rowOf(doc(), listOf(field("sender", "Finanzamt"))).addressee).isNull()
        assertThat(rowOf(doc(), listOf(field("addressee", "Mia"))).sender).isNull()
        val none = rowOf(doc())
        assertThat(none.sender).isNull()
        assertThat(none.addressee).isNull()
    }

    @Test
    fun `a blank party is missing and older rows are read by their name`() = runTest {
        val row = rowOf(
            doc(),
            listOf(
                field("sender", "   "),
                field(null, "Alt GmbH", name = "Sender Organization"),
                field(null, "Max", name = "Receiver Name"),
            ),
        )
        assertThat(row.sender).isNull()
        assertThat(row.addressee).isEqualTo("Max")
        assertThat(rowOf(doc(), listOf(field(null, "Alt GmbH", name = "Sender Organization"))).sender).isEqualTo("Alt GmbH")
    }

    @Test
    fun `the party name goes through the resolver seam`() = runTest {
        val names = PartyNameResolver { party, printed -> if (party == DocumentParty.ADDRESSEE) "Mia" else printed }
        val row = rowOf(doc(), listOf(field("sender", "Schule"), field("addressee", "Familie B.")), names = names)
        assertThat(row.sender).isEqualTo("Schule")
        assertThat(row.addressee).isEqualTo("Mia")
    }

    // ── action hint ──

    @Test
    fun `a deadline that is ahead or recent needs action`() = runTest {
        assertThat(rowOf(doc(), listOf(field("due_date", "15.10.2026"))).openActionCount).isEqualTo(1)
        assertThat(rowOf(doc(), listOf(field("due_date", "20.09.2026"))).openActionCount).isEqualTo(1)
    }

    @Test
    fun `a deadline long past needs no action`() = runTest {
        assertThat(rowOf(doc(), listOf(field("due_date", "01.06.2026"))).openActionCount).isEqualTo(0)
    }

    @Test
    fun `an amount to pay needs action, an amount that is only a total does not`() = runTest {
        assertThat(rowOf(doc(), listOf(field("total", "49,90 EUR", role = "TOTAL_DUE"))).openActionCount).isEqualTo(1)
        assertThat(rowOf(doc(), listOf(field("total", "49,90 EUR", role = "GROSS"))).openActionCount).isEqualTo(0)
    }

    @Test
    fun `an amount to pay with a deadline long past needs no action`() = runTest {
        val fields = listOf(field("total", "49,90 EUR", role = "TOTAL_DUE"), field("due_date", "01.06.2026"))
        assertThat(rowOf(doc(), fields).openActionCount).isEqualTo(0)
    }

    @Test
    fun `a statement, which asks nothing of its reader, never needs action`() = runTest {
        val fields = listOf(field("due_date", "15.10.2026"), field("total", "1 EUR", role = "TOTAL_DUE"))
        assertThat(rowOf(doc(type = "statement"), fields).openActionCount).isEqualTo(0)
    }

    @Test
    fun `a letter the model could not place in a family is judged by its fields`() = runTest {
        val fields = listOf(field("due_date", "15.10.2026"))
        assertThat(rowOf(doc(type = "free_form"), fields).openActionCount).isEqualTo(1)
    }

    @Test
    fun `the row asks the hint with the verified facts and shows its count`() = runTest {
        var seen: ActionFacts? = null
        val hint = ActionHint { facts -> seen = facts; 3 }
        val row = rowOf(doc(type = "bill"), listOf(field("due_date", "15.10.2026"), field("total", "9 EUR", role = "TOTAL_DUE")), hint = hint)
        assertThat(row.openActionCount).isEqualTo(3)
        assertThat(seen).isEqualTo(ActionFacts("d1", "bill", LocalDate.of(2026, 10, 15), true, today))
    }

    // ── title, thumbnail, batching ──

    @Test
    fun `a user title and a coded default title both reach the row as stored`() = runTest {
        val user = rowOf(doc(title = "My rent", isUserTitle = true)).document
        assertThat(user.displayTitle { "Scanned $it" }).isEqualTo("My rent")
        val coded = rowOf(doc(title = "Scanned 3 pages", titleCode = "scanned_pages", titleArgs = listOf("3"))).document
        assertThat(coded.displayTitle { "Gescannt: $it" }).isEqualTo("Gescannt: 3")
        assertThat(rowOf(doc(title = "AI title")).document.displayTitle { "x" }).isEqualTo("AI title")
    }

    @Test
    fun `the thumbnail is page one's image and absent without a page`() = runTest {
        assertThat(rowOf(doc(), firstPage = "/files/p1.jpg").firstPagePath).isEqualTo("/files/p1.jpg")
        assertThat(rowOf(doc()).firstPagePath).isNull()
    }

    @Test
    fun `a search query lists the matches and the fields are read in one batch`() = runTest {
        val repo = repository(doc())
        useCase(repo)("rent").first()
        verify(exactly = 1) { repo.searchDocuments("rent") }
        verify(exactly = 1) { repo.observeListFields() }
        verify(exactly = 1) { repo.observeFirstPagePaths() }
        verify(exactly = 0) { repo.getDocuments() }
    }
}
