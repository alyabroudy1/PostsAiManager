package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.database.dao.FormFillDao
import com.postsaimanager.core.data.database.entity.FormFieldEntity
import com.postsaimanager.core.data.database.entity.FormFillEntity
import com.postsaimanager.core.model.FormAwaitKind
import com.postsaimanager.core.model.FormAwaiting
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.NormBox
import com.postsaimanager.core.model.ReviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FormFillRepositoryImplTest {

    private val dao = InMemoryFormFillDao()
    private val repository = FormFillRepositoryImpl(dao, Dispatchers.Unconfined)

    private fun fill(id: String = "fill-d", status: FormFillStatus = FormFillStatus.UNDERSTANDING) = FormFill(
        id = id, documentId = "d", status = status, createdAt = 1, updatedAt = 1,
    )

    private fun field(id: String, page: Int = 1, order: Int = 0, value: String? = null, review: ReviewState = ReviewState.UNREVIEWED) = FormField(
        id = id, formFillId = "fill-d", documentId = "d", page = page, labelText = id, labelBox = NormBox(0.1f, 0.2f, 0.3f, 0.4f),
        fillBox = NormBox(0.5f, 0.6f, 0.7f, 0.8f), kind = FormFieldKind.CHOICE, section = "Kind", options = listOf("Ja", "Nein"),
        dataKey = "swim_level", role = FormRole.SUBJECT, confidence = 0.75f, value = value, reviewState = review, orderIndex = order,
    )

    @Test
    fun `a fill round-trips with its roles, confirmed roles, awaiting state and round`() = runTest {
        val stored = fill(status = FormFillStatus.ASK_ROLE).copy(
            roleProfiles = mapOf(FormRole.SUBJECT to "ahmad", FormRole.GUARDIAN to "me"),
            confirmedRoles = setOf(FormRole.SUBJECT),
            localeTag = "de",
            currentFieldId = "f1",
            awaiting = FormAwaiting(FormAwaitKind.ROLE, role = FormRole.PAYER),
            roundAsked = 3,
            readingKey = "v4-0123456789abcdef",
            conversationId = "conv-d",
        )

        repository.saveFill(stored)

        assertThat(repository.getFill("fill-d")).isEqualTo(stored)
        assertThat(repository.fillForDocument("d")).isEqualTo(stored)
        assertThat(repository.observeFill("fill-d").first()).isEqualTo(stored)
        assertThat(repository.fillForDocument("other")).isNull()
    }

    @Test
    fun `a field round-trips with its boxes and options and comes back in page and reading order`() = runTest {
        repository.saveFill(fill())
        repository.saveFields("fill-d", listOf(field("b", page = 2), field("a", page = 1, order = 1), field("c", page = 1, order = 0)))

        val fields = repository.fields("fill-d")

        assertThat(fields.map { it.id }).containsExactly("c", "a", "b").inOrder()
        assertThat(fields.first().labelBox).isEqualTo(NormBox(0.1f, 0.2f, 0.3f, 0.4f))
        assertThat(fields.first().options).containsExactly("Ja", "Nein").inOrder()
        assertThat(fields.first().role).isEqualTo(FormRole.SUBJECT)
        assertThat(repository.observeFields("fill-d").first()).isEqualTo(fields)
    }

    @Test
    fun `a re-run never overwrites a field the user answered, confirmed or skipped`() = runTest {
        repository.saveFill(fill())
        repository.saveFields("fill-d", listOf(field("typed"), field("confirmed"), field("skipped"), field("untouched")))
        repository.setValue("typed", "Nein", FormValueSource.USER, ReviewState.EDITED, "ahmad", nowMs = 5)
        repository.setValue("confirmed", "Ja", FormValueSource.PROFILE, ReviewState.CONFIRMED, "ahmad", nowMs = 5)
        repository.setSkipped("skipped", true, nowMs = 5)

        repository.saveFields(
            "fill-d",
            listOf(field("typed", value = "machine"), field("confirmed", value = "machine"), field("skipped", value = "machine"), field("untouched", value = "machine")),
        )

        val byId = repository.fields("fill-d").associateBy { it.id }
        assertThat(byId.getValue("typed").value).isEqualTo("Nein")
        assertThat(byId.getValue("typed").valueSource).isEqualTo(FormValueSource.USER)
        assertThat(byId.getValue("confirmed").reviewState).isEqualTo(ReviewState.CONFIRMED)
        assertThat(byId.getValue("skipped").skipped).isTrue()
        assertThat(byId.getValue("skipped").value).isNull()
        assertThat(byId.getValue("untouched").value).isEqualTo("machine")
    }

    @Test
    fun `answering clears skipped and reconfirm, and an empty value empties the field`() = runTest {
        repository.saveFill(fill())
        repository.saveFields("fill-d", listOf(field("a").copy(skipped = true, reconfirm = true)))

        repository.setValue("a", "Ja", FormValueSource.USER, ReviewState.EDITED, "ahmad", nowMs = 9)
        val answered = repository.fields("fill-d").single()
        assertThat(answered.skipped).isFalse()
        assertThat(answered.reconfirm).isFalse()
        assertThat(answered.updatedAt).isEqualTo(9)

        repository.setValue("a", null, FormValueSource.USER, ReviewState.UNREVIEWED, null, nowMs = 10)
        val emptied = repository.fields("fill-d").single()
        assertThat(emptied.value).isNull()
        assertThat(emptied.valueSource).isEqualTo(FormValueSource.NONE)
    }

    @Test
    fun `deleting the fields drops reviewed ones too`() = runTest {
        repository.saveFill(fill())
        repository.saveFields("fill-d", listOf(field("a"), field("b")))
        repository.setValue("a", "Ja", FormValueSource.USER, ReviewState.EDITED, "ahmad", nowMs = 5)

        repository.deleteFields("fill-d")

        assertThat(repository.fields("fill-d")).isEmpty()
        assertThat(repository.getFill("fill-d")).isNotNull()
    }

    @Test
    fun `clearing the values forgets answers about another person but keeps the fields`() = runTest {
        repository.saveFill(fill())
        repository.saveFields("fill-d", listOf(field("a"), field("b")))
        repository.setValue("a", "Ja", FormValueSource.USER, ReviewState.EDITED, "ahmad", nowMs = 5)
        repository.setSkipped("b", true, nowMs = 5)

        repository.clearValues("fill-d", nowMs = 8)

        val fields = repository.fields("fill-d")
        assertThat(fields).hasSize(2)
        assertThat(fields.all { it.value == null && it.reviewState == ReviewState.UNREVIEWED && !it.skipped }).isTrue()
    }

    @Test
    fun `an unreadable stored value reads as absent instead of failing the row`() = runTest {
        dao.upsertFill(FormFillEntity("fill-d", "d", "NO_SUCH_STATUS", "not json", "[", null, null, null, "{", 0, 1, 1))
        dao.upsertFields(
            listOf(FormFieldEntity("f", "fill-d", "d", 1, "Label", "x", "y", "NO_SUCH_KIND", null, "oops", null, "NO_ROLE", 0.5f, null, "NO_SOURCE", null, "NO_STATE", false, null, false, false, 0, 1)),
        )

        val fill = repository.getFill("fill-d")!!
        val field = repository.fields("fill-d").single()

        assertThat(fill.status).isEqualTo(FormFillStatus.UNDERSTANDING)
        assertThat(fill.roleProfiles).isEmpty()
        assertThat(fill.confirmedRoles).isEmpty()
        assertThat(fill.awaiting).isNull()
        assertThat(field.kind).isEqualTo(FormFieldKind.TEXT)
        assertThat(field.role).isNull()
        assertThat(field.labelBox).isNull()
        assertThat(field.options).isEmpty()
        assertThat(field.valueSource).isEqualTo(FormValueSource.NONE)
        assertThat(field.reviewState).isEqualTo(ReviewState.UNREVIEWED)
    }
}

private class InMemoryFormFillDao : FormFillDao {
    private val fills = MutableStateFlow<List<FormFillEntity>>(emptyList())
    private val fields = MutableStateFlow<List<FormFieldEntity>>(emptyList())

    override suspend fun getFill(id: String) = fills.value.firstOrNull { it.id == id }
    override fun observeFill(id: String): Flow<FormFillEntity?> = fills.map { l -> l.firstOrNull { it.id == id } }
    override suspend fun latestForDocument(documentId: String) = fills.value.filter { it.documentId == documentId }.maxByOrNull { it.createdAt }
    override suspend fun upsertFill(fill: FormFillEntity) {
        fills.value = fills.value.filterNot { it.id == fill.id } + fill
    }

    override suspend fun getFields(fillId: String) = sorted(fillId)
    override fun observeFields(fillId: String): Flow<List<FormFieldEntity>> = fields.map { sortedOf(it, fillId) }
    override suspend fun getField(id: String) = fields.value.firstOrNull { it.id == id }
    override suspend fun upsertFields(fields: List<FormFieldEntity>) {
        val ids = fields.map { it.id }.toSet()
        this.fields.value = this.fields.value.filterNot { it.id in ids } + fields
    }

    override suspend fun deleteFields(fillId: String) {
        fields.value = fields.value.filterNot { it.formFillId == fillId }
    }

    private fun sorted(fillId: String) = sortedOf(fields.value, fillId)
    private fun sortedOf(list: List<FormFieldEntity>, fillId: String) =
        list.filter { it.formFillId == fillId }.sortedWith(compareBy({ it.page }, { it.orderIndex }))
}
