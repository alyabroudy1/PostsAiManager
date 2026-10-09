package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** What a date or an amount means, chosen by the user: only the meanings of its kind, stored on the row as theirs. */
class SetFieldMeaningUseCaseTest {

    private val documents = FakeDocumentRepository()
    private val setMeaning = SetFieldMeaningUseCase(documents)

    private fun field(id: String, slotKey: String?, type: ExtractedFieldType = ExtractedFieldType.TEXT, role: String? = null) = ExtractedData(
        id = id, documentId = "d1", fieldName = id, fieldValue = "v", fieldType = type, confidence = 0.6f, slotKey = slotKey, role = role,
    )

    private suspend fun row(id: String) = documents.observeExtractedData("d1").first().single { it.id == id }

    private suspend fun seed(vararg fields: ExtractedData) {
        documents.seed(testDocument(id = "d1"))
        documents.seedExtracted("d1", *fields)
    }

    @Test
    fun `the kind of a value comes from its slot, its found code or its type, and other values have none`() {
        assertThat(FieldMeanings.kindOf(field("a", "due_date"))).isEqualTo(MeaningKind.DATE)
        assertThat(FieldMeanings.kindOf(field("b", "total"))).isEqualTo(MeaningKind.AMOUNT)
        assertThat(FieldMeanings.kindOf(field("c", "found:DATE:2"))).isEqualTo(MeaningKind.DATE)
        assertThat(FieldMeanings.kindOf(field("d", "found:AMOUNT:1"))).isEqualTo(MeaningKind.AMOUNT)
        assertThat(FieldMeanings.kindOf(field("e", null, ExtractedFieldType.DATE))).isEqualTo(MeaningKind.DATE)
        assertThat(FieldMeanings.kindOf(field("f", "sender"))).isNull()
        assertThat(FieldMeanings.kindOf(field("g", null))).isNull()
    }

    @Test
    fun `a date chooses among the meanings of a date`() = runTest {
        seed(field("due", "due_date"))

        val result = setMeaning("d1", "due", "APPOINTMENT")

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        assertThat(row("due").role).isEqualTo("meaning:APPOINTMENT")
        assertThat(row("due").reviewState).isEqualTo(ReviewState.CONFIRMED)
    }

    @Test
    fun `an amount cannot be given the meaning of a date, nor a value that has none any meaning`() = runTest {
        seed(field("total", "total"), field("sender", "sender"))

        assertThat(setMeaning("d1", "total", "APPOINTMENT")).isInstanceOf(PamResult.Error::class.java)
        assertThat(setMeaning("d1", "sender", "TOTAL_DUE")).isInstanceOf(PamResult.Error::class.java)
        assertThat(setMeaning("d1", "missing", "DUE_DATE")).isInstanceOf(PamResult.Error::class.java)

        assertThat(row("total").role).isNull()
        assertThat(row("sender").role).isNull()
    }

    @Test
    fun `none of these clears the meaning and keeps the choice`() = runTest {
        seed(field("due", "due_date", role = "meaning:DUE_DATE"))

        setMeaning("d1", "due", null)

        assertThat(row("due").role).isNull()
        assertThat(row("due").reviewState).isEqualTo(ReviewState.CONFIRMED)
    }
}
