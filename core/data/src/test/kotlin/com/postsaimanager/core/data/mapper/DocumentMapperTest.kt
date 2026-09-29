package com.postsaimanager.core.data.mapper

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

class DocumentMapperTest {

    private val mapper = DocumentMapper()

    @Test
    fun `an extracted field keeps its slot, role, origin, both confidences, evidence and box through storage`() {
        val field = ExtractedData(
            id = "f1", documentId = "d1", fieldName = "Amount", fieldValue = "64,98 €",
            fieldType = ExtractedFieldType.OTHER, confidence = 0.4f, pageNumber = 2,
            slotKey = "total", role = "TOTAL_DUE", origin = "MODEL_CHOICE", aiConfidence = 0.9f,
            evidence = "Gesamtbetrag 64,98 €", bbox = TextBounds(0.1f, 0.2f, 0.6f, 0.25f),
            engineVersion = "extraction-v2-1",
        )

        val back = mapper.extractedDataToDomain(mapper.extractedDataToEntity(field))

        assertThat(back).isEqualTo(field)
    }

    @Test
    fun `a stored box that cannot be read is absent, not a failure`() {
        val entity = mapper.extractedDataToEntity(
            ExtractedData("f", "d", "n", "v", ExtractedFieldType.TEXT, 0.9f),
        ).copy(bbox = "{not json")

        assertThat(mapper.extractedDataToDomain(entity).bbox).isNull()
    }

    @Test
    fun `a document keeps what the model understood about it`() {
        val document = Document(
            id = "d1", title = "Nordlicht: Zahlungserinnerung", sourceType = SourceType.CAMERA,
            createdAt = 1, modifiedAt = 2,
            extractionType = "reminder_dunning", extractionTypeConfidence = 0.9f, extractorVersion = "extraction-v2-1",
            isUserTitle = true, summary = "Pay 64,98 € by 14 days.",
            suggestedQuestions = listOf("When is it due?", "How much is the fee?", "Who is it from?"),
            titleCode = "scanned_pages", titleArgs = listOf("3"),
        )

        val back = mapper.toDomain(mapper.toEntity(document))

        assertThat(back).isEqualTo(document)
    }

    @Test
    fun `a document stored before the columns existed reads with empty questions and no code`() {
        val entity = DocumentEntity(
            id = "d", title = "T", status = "EXTRACTED", documentType = null, language = null,
            sourceType = "CAMERA", thumbnailPath = null, pageCount = 1, createdAt = 1, modifiedAt = 1,
        )

        val doc = mapper.toDomain(entity)

        assertThat(doc.suggestedQuestions).isEmpty()
        assertThat(doc.titleArgs).isEmpty()
        assertThat(doc.titleCode).isNull()
        assertThat(doc.isUserTitle).isFalse()
    }
}
