package com.postsaimanager.core.domain.extraction.actions

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.document.SetFieldMeaningUseCase
import com.postsaimanager.core.domain.document.ReprocessOverwritePolicy
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** A date or an amount the user gave a meaning drives the action that states it, and keeps driving it through a re-read. */
class ActionBindingTest {

    private val documents = FakeDocumentRepository()
    private val setMeaning = SetFieldMeaningUseCase(documents)
    private val pay = ActionItem("pay", mapOf("date" to "due_date", "amount" to "total"))

    private fun date(id: String, slot: String, value: String) = ExtractedData(
        id = id, documentId = "d1", fieldName = slot, fieldValue = value, fieldType = ExtractedFieldType.DATE, confidence = 0.9f,
        slotKey = slot, machineValue = value,
    )

    private fun amount(id: String, slot: String, value: String) = ExtractedData(
        id = id, documentId = "d1", fieldName = slot, fieldValue = value, fieldType = ExtractedFieldType.TEXT, confidence = 0.9f,
        slotKey = slot, machineValue = value,
    )

    private val boundDate = date("a", "due_date", "15.10.2026")
    private val otherDate = date("b", "found:DATE:1", "20.11.2026")
    private val boundAmount = amount("c", "total", "100,00 EUR")
    private val otherAmount = amount("d", "found:AMOUNT:1", "80,00 EUR")

    private suspend fun seed() {
        documents.seed(testDocument(id = "d1").copy(actionItems = listOf(pay)))
        documents.seedExtracted("d1", boundDate, otherDate, boundAmount, otherAmount)
    }

    private suspend fun line(fields: List<ExtractedData>) = ActionLines.resolve(documents.getDocumentById("d1").let { (it as com.postsaimanager.core.common.result.PamResult.Success).data.actionItems }, fields).single()

    @Test
    fun `without a chosen meaning the action states the fields the reading bound`() = runTest {
        seed()

        val line = line(documents.observeExtractedData("d1").first())

        assertThat(line.date?.date.toString()).isEqualTo("2026-10-15")
        assertThat(line.amount).isEqualTo("100,00 EUR")
    }

    @Test
    fun `a date set as the due date and an amount set as the amount to pay become the pay action's`() = runTest {
        seed()

        setMeaning("d1", "b", "DUE_DATE")
        setMeaning("d1", "d", "TOTAL_DUE")

        val line = line(documents.observeExtractedData("d1").first())
        assertThat(line.date?.date.toString()).isEqualTo("2026-11-20")
        assertThat(line.amount).isEqualTo("80,00 EUR")
        assertThat(line.rows.map { it.id }).containsAtLeast("b", "d")
    }

    @Test
    fun `after a re-read of the values and of the actions the action still follows the chosen meaning`() = runTest {
        seed()
        setMeaning("d1", "b", "DUE_DATE")
        setMeaning("d1", "d", "TOTAL_DUE")

        // The next reading finds the same values again (new ids, the reading's own bindings), and chooses the same pay action.
        val stored = documents.observeExtractedData("d1").first()
        val fresh = listOf(boundDate, otherDate, boundAmount, otherAmount).map { it.copy(id = "new-${it.id}") }
        val merged = MergeExtractionUseCase()(stored, fresh, "v2", now = 5L, newId = { "rev" })
        val document = (documents.getDocumentById("d1") as com.postsaimanager.core.common.result.PamResult.Success).data
        val actions = ReprocessOverwritePolicy.applyActions(document, DocumentUnderstanding(actionItems = listOf(pay))).actionItems

        val line = ActionLines.resolve(actions, merged.toPersist).single()

        assertThat(line.date?.date.toString()).isEqualTo("2026-11-20")
        assertThat(line.amount).isEqualTo("80,00 EUR")
    }

    @Test
    fun `a meaning the reading itself gave does not rebind the action`() = runTest {
        seed()
        val machineMeant = otherDate.copy(role = "meaning:DUE_DATE")

        val line = ActionLines.resolve(listOf(pay), listOf(boundDate, machineMeant, boundAmount)).single()

        assertThat(line.date?.date.toString()).isEqualTo("2026-10-15")
    }

    @Test
    fun `an appointment the user chose is the date of an attend action`() = runTest {
        documents.seed(testDocument(id = "d1").copy(actionItems = listOf(ActionItem("attend"))))
        documents.seedExtracted("d1", otherDate)
        setMeaning("d1", "b", "APPOINTMENT")

        val line = ActionLines.resolve(
            (documents.getDocumentById("d1") as com.postsaimanager.core.common.result.PamResult.Success).data.actionItems,
            documents.observeExtractedData("d1").first(),
        ).single()

        assertThat(line.date?.date.toString()).isEqualTo("2026-11-20")
    }
}
