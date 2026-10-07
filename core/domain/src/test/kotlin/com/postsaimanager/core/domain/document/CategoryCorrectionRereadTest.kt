package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.zones.ZoneScoringInterpreter
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.ValueSource
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * "Change type" is a re-read with the person's category as context: what the person already edited survives it, field by field, and the
 * category stays theirs. The re-read here is the real first stage of the pipeline (a fake model, `forcedFamily` as the worker passes it),
 * merged into stored fields the way `DocumentProcessingPipeline` merges them.
 */
class CategoryCorrectionRereadTest {

    private val letter = Letters.invoice

    private fun reread(forced: String?): Pair<DocumentUnderstanding, FakePromptSession> {
        val session = FakePromptSession().apply {
            scorer = { c ->
                when {
                    c.contains("«28.09.2026»") && c.contains("the date of the letter itself") -> 5.0
                    c.contains("«1.284,50 €»") && c.contains("the main amount") -> 5.0
                    c.contains("«Musterfirma GmbH»") && c.contains("the sender") -> 5.0
                    c.contains("«Erika Mustermann»") && c.contains("the addressee") -> 5.0
                    else -> -5.0
                }
            }
            responder = { _, _ -> "\"text\"" }
        }
        val interpreter = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096, topicsInFirstStage = false)
        val result = runBlocking {
            ExtractionV2Pipeline().run(letter.pages, interpreter, 4096, stages = ExtractionV2Pipeline.Stages.FIRST, forcedFamily = forced)
        }
        return ExtractionV2Adapter().adapt(result) to session
    }

    private fun fieldsOf(understanding: DocumentUnderstanding): List<ExtractedData> =
        UnderstandingToFields.invoke("doc", understanding) { it }

    /** What the person did to the stored reading: corrected the sender and the amount. */
    private fun edited(stored: List<ExtractedData>): List<ExtractedData> = stored.map { f ->
        when (f.slotKey) {
            "total" -> f.copy(fieldValue = "999,00 €", source = ValueSource.USER, isConfirmed = true, reviewState = ReviewState.EDITED)
            "sender" -> f.copy(fieldValue = "Stadtwerke Beispielstadt", source = ValueSource.USER, isConfirmed = true, reviewState = ReviewState.EDITED)
            else -> f
        }
    }

    @Test
    fun `the fields a person edited survive the re-read that follows a change of category`() {
        val first = fieldsOf(reread(forced = null).first)
        val stored = edited(first)
        assertThat(stored.first { it.slotKey == "total" }.fieldValue).isEqualTo("999,00 €")

        // The re-read, with the category the person gave as context (the worker passes it as forcedFamily).
        val (understanding, session) = reread(forced = "receipt")
        assertThat(session.opens.all { it.contains("The user says this document is a receipt.") }).isTrue()
        val fresh = fieldsOf(understanding)
        // The machine reads the amount and the sender as before: the edit and the machine's value differ.
        assertThat(fresh.first { it.slotKey == "total" }.fieldValue).isNotEqualTo("999,00 €")

        val merged = MergeExtractionUseCase().invoke(stored, fresh, engineVersion = "v2", now = 1_000L, newId = { "$it-rev" })
        val total = merged.toPersist.first { it.slotKey == "total" }
        val sender = merged.toPersist.first { it.slotKey == "sender" }
        assertThat(total.fieldValue).isEqualTo("999,00 €")
        assertThat(total.source).isEqualTo(ValueSource.USER)
        assertThat(sender.fieldValue).isEqualTo("Stadtwerke Beispielstadt")
        assertThat(sender.source).isEqualTo(ValueSource.USER)
        // A value nobody touched is the new reading's, and the edits are not dropped.
        assertThat(merged.idsToDelete).isEmpty()
    }

    @Test
    fun `the category stays the person's through the re-read, whatever the model reads`() {
        val before = Document(
            id = "d", title = "t", sourceType = SourceType.CAMERA, createdAt = 1, modifiedAt = 1,
            extractionType = "receipt", familySource = FamilySource.USER,
        )
        val (understanding, _) = reread(forced = "receipt")
        // The first stage carries the person's category, and the policy keeps it as theirs.
        val afterFirst = ReprocessOverwritePolicy.applyFamily(before, understanding, forcedFamily = "receipt", provisional = true)
        assertThat(afterFirst.extractionType).isEqualTo("receipt")
        assertThat(afterFirst.familySource).isEqualTo(FamilySource.USER)
        // The second stage reads the document as an invoice, and cannot change it.
        val invoice = understanding.copy(documentType = "invoice_bill")
        val afterSecond = ReprocessOverwritePolicy.applyFamily(afterFirst, invoice)
        assertThat(afterSecond.extractionType).isEqualTo("receipt")
        assertThat(afterSecond.familySource).isEqualTo(FamilySource.USER)
    }
}
