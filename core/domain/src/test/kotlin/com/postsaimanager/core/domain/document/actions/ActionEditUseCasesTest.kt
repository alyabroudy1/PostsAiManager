package com.postsaimanager.core.domain.document.actions

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.ReprocessOverwritePolicy
import com.postsaimanager.core.domain.extraction.actions.ActionLines
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ActionSource
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Editing, deleting and adding an action, and what a re-read does to them afterwards (nothing: the person's say stays). */
class ActionEditUseCasesTest {

    private val documents = FakeDocumentRepository()
    private val edit = EditActionUseCase(documents)
    private val delete = DeleteActionUseCase(documents)
    private val add = AddActionUseCase(documents)

    private val pay = ActionItem("pay", mapOf("date" to "due_date", "amount" to "total", "party" to "sender"))
    private val reply = ActionItem("reply", mapOf("date" to "due_date"))

    private suspend fun seed(vararg items: ActionItem) {
        documents.seed(testDocument(id = "d1").copy(actionItems = items.toList()))
    }

    private suspend fun stored() = (documents.getDocumentById("d1") as PamResult.Success).data.actionItems

    private fun reading(vararg items: ActionItem) = DocumentUnderstanding(actionItems = items.toList())

    /** What a second stage does with the stored document: [ReprocessOverwritePolicy.applyActions] on the document as it is now. */
    private suspend fun reread(vararg items: ActionItem) {
        val document = (documents.getDocumentById("d1") as PamResult.Success).data
        documents.updateDocument(ReprocessOverwritePolicy.applyActions(document, reading(*items)))
    }

    // ── edit ──

    @Test
    fun `editing keeps the position, makes the action the user's and remembers the model action it came from`() = runTest {
        seed(pay, reply)

        val result = edit("d1", reply, ActionEdit(kind = "contact", text = "  Call the office  ", dueDate = "2026-11-05"))

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        val items = stored()
        assertThat(items[0]).isEqualTo(pay)
        assertThat(items[1].kind).isEqualTo("contact")
        assertThat(items[1].text).isEqualTo("Call the office")
        assertThat(items[1].dueDate).isEqualTo("2026-11-05")
        assertThat(items[1].source).isEqualTo(ActionSource.USER)
        assertThat(items[1].origin).isEqualTo("reply")
    }

    @Test
    fun `a part the new kind does not state is no longer bound`() = runTest {
        seed(pay)

        edit("d1", pay, ActionEdit(kind = "attend"))

        // attend states a date only: the amount and the payee are dropped, the date stays.
        assertThat(stored().single().bindings).containsExactly("date", "due_date")
    }

    @Test
    fun `an unknown kind, an unreadable date or a vanished action changes nothing`() = runTest {
        seed(pay)

        assertThat(edit("d1", pay, ActionEdit(kind = "no-such-kind"))).isInstanceOf(PamResult.Error::class.java)
        assertThat(edit("d1", reply, ActionEdit(kind = "reply"))).isInstanceOf(PamResult.Error::class.java)
        edit("d1", pay, ActionEdit(kind = "pay", dueDate = "next week"))

        assertThat(stored().single().dueDate).isNull()
    }

    @Test
    fun `an edited action survives every re-read, and the model choosing its old kind again adds nothing`() = runTest {
        seed(pay, reply)
        edit("d1", reply, ActionEdit(kind = "contact", text = "Call the office"))

        // The second stage reads the letter again and still chooses pay and reply.
        reread(pay, reply)

        val items = stored()
        assertThat(items.map { it.kind }).containsExactly("pay", "contact").inOrder()
        assertThat(items.single { it.kind == "contact" }.text).isEqualTo("Call the office")
        assertThat(items.single { it.kind == "pay" }.source).isEqualTo(ActionSource.MODEL)
    }

    // ── delete ──

    @Test
    fun `a deleted model action is gone from the letter and a re-read does not bring it back`() = runTest {
        seed(pay, reply)

        delete("d1", pay)
        reread(pay, reply)

        val live = ActionLines.resolve(stored(), emptyList())
        assertThat(live.map { it.kind.id }).containsExactly("reply")
        assertThat(stored().any { it.kind == "pay" && it.removed }).isTrue()
    }

    @Test
    fun `a deleted action does not count and is not stated`() = runTest {
        seed(pay)
        delete("d1", pay)

        assertThat(stored().count { !it.removed }).isEqualTo(0)
        assertThat(ActionLines.resolve(stored(), emptyList())).isEmpty()
    }

    @Test
    fun `an action the user added is simply removed when they delete it`() = runTest {
        seed()
        add("d1", ActionEdit(kind = "other_action", text = "Ask the neighbour"))
        val added = stored().single()

        delete("d1", added)

        assertThat(stored()).isEmpty()
    }

    // ── add ──

    @Test
    fun `an added action is the user's, rendered with their wording and date, and survives a re-read that chooses none`() = runTest {
        seed(pay)

        add("d1", ActionEdit(kind = "other_action", text = "Ask the neighbour", dueDate = "2026-12-01"))
        reread() // a reading that chose no action at all

        val items = stored()
        assertThat(items.map { it.kind }).containsExactly("other_action").inOrder()
        val line = ActionLines.resolve(items, emptyList()).single()
        assertThat(line.text).isEqualTo("Ask the neighbour")
        assertThat(line.date?.date.toString()).isEqualTo("2026-12-01")
    }

    @Test
    fun `an added action of a kind the model also chooses is not duplicated by a re-read`() = runTest {
        seed()
        add("d1", ActionEdit(kind = "pay", text = "Pay the rent first"))

        reread(pay)

        assertThat(stored().map { it.kind }).containsExactly("pay")
        assertThat(stored().single().text).isEqualTo("Pay the rent first")
    }

    @Test
    fun `an action without wording needs one when its kind says nothing`() = runTest {
        seed()

        assertThat(add("d1", ActionEdit(kind = "other_action"))).isInstanceOf(PamResult.Error::class.java)
        assertThat(add("d1", ActionEdit(kind = "reply"))).isInstanceOf(PamResult.Success::class.java)
        assertThat(stored().single().kind).isEqualTo("reply")
    }

    // ── the date the user gave wins over the bound field ──

    @Test
    fun `the user's date wins over the date of the bound field`() {
        val field = ExtractedData(
            id = "f1", documentId = "d1", fieldName = "Due date", fieldValue = "15.10.2026", fieldType = ExtractedFieldType.DATE,
            confidence = 0.9f, slotKey = "due_date",
        )
        val item = pay.copy(source = ActionSource.USER, dueDate = "2026-11-20")

        val line = ActionLines.resolve(listOf(item), listOf(field)).single()

        assertThat(line.date?.date.toString()).isEqualTo("2026-11-20")
        assertThat(line.valueRows).isEmpty()
    }
}
