package com.postsaimanager.core.data.mapper

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.FieldAlternative
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.model.TitleSource
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
            titleCode = "scanned_pages", titleArgs = listOf("3"), titleSource = TitleSource.USER,
            summarySource = SummarySource.MODEL,
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

    @Test
    fun `a document keeps its family, topics, sources, template and summary provenance through storage`() {
        val document = Document(
            id = "d1", title = "Rechnung · Nordlicht", sourceType = SourceType.CAMERA, createdAt = 1, modifiedAt = 2,
            extractionType = "invoice_bill", extractionTypeConfidence = 0.8f,
            topics = listOf("telecom", "bank_finance"), familySource = FamilySource.USER,
            titleSource = TitleSource.COMPOSED, titleCode = "composed", titleArgs = listOf("invoice_bill", "Nordlicht"),
            summary = "Pay 64,98 EUR.", summarySource = SummarySource.TEMPLATE,
            summaryCode = "template", summaryArgs = listOf("Nordlicht", "64,98 EUR"), layoutTemplate = "din5008_b",
        )

        assertThat(mapper.toDomain(mapper.toEntity(document))).isEqualTo(document)
    }

    @Test
    fun `a document keeps its action lines through storage, and one stored before v18 has none`() {
        val document = Document(
            id = "d1", title = "T", sourceType = SourceType.CAMERA, createdAt = 1, modifiedAt = 2, titleSource = TitleSource.MODEL,
            actionItems = listOf("Zahle 64,98 € bis zum 15.10.2026.", "Einspruch bis zum 02.09.2026 möglich."),
        )
        assertThat(mapper.toDomain(mapper.toEntity(document))).isEqualTo(document)
        assertThat(mapper.toEntity(document.copy(actionItems = emptyList())).actionItems).isNull()
        assertThat(
            mapper.toDomain(
                DocumentEntity(
                    id = "d", title = "T", status = "EXTRACTED", documentType = null, language = null, sourceType = "CAMERA",
                    thumbnailPath = null, pageCount = 1, createdAt = 1, modifiedAt = 1, actionItems = null,
                ),
            ).actionItems,
        ).isEmpty()
    }

    @Test
    fun `a document stored before v15 derives its title and summary sources from what it already says`() {
        fun read(isUserTitle: Boolean = false, titleCode: String? = null, summary: String? = null) =
            mapper.toDomain(
                DocumentEntity(
                    id = "d", title = "T", status = "EXTRACTED", documentType = null, language = null,
                    sourceType = "CAMERA", thumbnailPath = null, pageCount = 1, createdAt = 1, modifiedAt = 1,
                    isUserTitle = isUserTitle, titleCode = titleCode, summary = summary,
                ),
            )

        assertThat(read(isUserTitle = true).titleSource).isEqualTo(TitleSource.USER)
        assertThat(read(titleCode = "scanned_pages").titleSource).isEqualTo(TitleSource.DEFAULT)
        assertThat(read().titleSource).isEqualTo(TitleSource.MODEL)
        assertThat(read(summary = "s").summarySource).isEqualTo(SummarySource.MODEL)
        assertThat(read().summarySource).isNull()
        assertThat(read().familySource).isEqualTo(FamilySource.MODEL)
        assertThat(read().topics).isEmpty()
    }

    @Test
    fun `a title that became real words is stored as the model's even if the source was not updated`() {
        val entity = mapper.toEntity(
            Document(id = "d", title = "Real words", sourceType = SourceType.CAMERA, createdAt = 1, modifiedAt = 1),
        )

        assertThat(entity.titleSource).isEqualTo("MODEL")
    }

    @Test
    fun `a field keeps its review state and alternatives through storage`() {
        val alt = FieldAlternative(value = "Hauptstr. 1", normalized = "hauptstr 1", score = 0.4f, page = 1, bbox = TextBounds(0f, 0f, 1f, 1f))
        for (state in ReviewState.entries) {
            val field = ExtractedData(
                id = "f", documentId = "d", fieldName = "n", fieldValue = "v", fieldType = ExtractedFieldType.TEXT,
                confidence = 0.9f, reviewState = state, alternatives = listOf(alt),
            )

            val back = mapper.extractedDataToDomain(mapper.extractedDataToEntity(field))

            assertThat(back.reviewState).isEqualTo(state)
            assertThat(back.alternatives).containsExactly(alt)
        }
    }

    @Test
    fun `an unknown stored review state reads as unreviewed and unreadable alternatives as none`() {
        val entity = mapper.extractedDataToEntity(
            ExtractedData("f", "d", "n", "v", ExtractedFieldType.TEXT, 0.9f),
        ).copy(reviewState = "WHATEVER", alternatives = "{not json")

        val back = mapper.extractedDataToDomain(entity)

        assertThat(back.reviewState).isEqualTo(ReviewState.UNREVIEWED)
        assertThat(back.alternatives).isEmpty()
    }
}
