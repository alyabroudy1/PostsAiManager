package com.postsaimanager.core.domain.timeline

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import org.junit.jupiter.api.Test

class ReferenceKeysTest {

    private var next = 0
    private fun field(slot: String, value: String, deleted: Boolean = false) = ExtractedData(
        id = "f${next++}", documentId = "d1", fieldName = slot, fieldValue = value, fieldType = ExtractedFieldType.REFERENCE_NUMBER,
        confidence = 0.9f, slotKey = slot, deletedByUser = deleted,
    )

    @Test
    fun `only the shape is normalised, so case, spaces, dashes and slashes do not matter`() {
        assertThat(ReferenceKeys.normalise("BG 123-456/7")).isEqualTo("BG1234567")
        assertThat(ReferenceKeys.normalise("bg1234567")).isEqualTo("BG1234567")
        assertThat(ReferenceKeys.normalise("٣٤٥٦٧")).isEqualTo("34567")
    }

    @Test
    fun `look-alike letters inside a digit run read as digits, words are left alone`() {
        assertThat(ReferenceKeys.normalise("12345BGOOO7777")).isEqualTo(ReferenceKeys.normalise("12345BG0007777"))
        assertThat(ReferenceKeys.normalise("KD-l23I")).isEqualTo("KD1231")
        assertThat(ReferenceKeys.normalise("Olli 1234")).isEqualTo("OLLI1234")
    }

    @Test
    fun `a value that is too short or holds no digit is no reference`() {
        assertThat(ReferenceKeys.normalise("A12")).isNull()
        assertThat(ReferenceKeys.normalise("Jobcenter")).isNull()
    }

    @Test
    fun `the keys of a document are its reference slots, listed ones split, a deleted value ignored`() {
        val keys = ReferenceKeys.of(
            listOf(
                field("case_no", "BG 3141592"),
                field("customer_no", "K-998877"),
                field("cited_references", "AZ 11/2026, AZ 12/2026"),
                field("case_no", "XX 000111", deleted = true),
                field("total", "64,98 EUR"),
            ),
        )
        assertThat(keys).containsExactly("BG3141592", "K998877", "AZ112026", "AZ122026")
    }
}
