package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.usecase.ObserveChatVisibleDocumentsUseCase
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.TitleSource
import org.junit.jupiter.api.Test

class ReprocessOverwritePolicyTest {

    private fun doc(family: FamilySource = FamilySource.MODEL, summary: SummarySource? = null) = Document(
        id = "d", title = "t", sourceType = SourceType.CAMERA, createdAt = 1, modifiedAt = 1,
        familySource = family, summarySource = summary,
    )

    @Test
    fun `the family is overwritten only while the model chose it`() {
        assertThat(ReprocessOverwritePolicy.mayOverwriteFamily(doc(family = FamilySource.MODEL))).isTrue()
        assertThat(ReprocessOverwritePolicy.mayOverwriteFamily(doc(family = FamilySource.USER))).isFalse()
    }

    @Test
    fun `the summary is overwritten unless a person wrote it`() {
        assertThat(ReprocessOverwritePolicy.mayOverwriteSummary(doc(summary = null))).isTrue()
        assertThat(ReprocessOverwritePolicy.mayOverwriteSummary(doc(summary = SummarySource.MODEL))).isTrue()
        assertThat(ReprocessOverwritePolicy.mayOverwriteSummary(doc(summary = SummarySource.TEMPLATE))).isTrue()
        assertThat(ReprocessOverwritePolicy.mayOverwriteSummary(doc(summary = SummarySource.USER))).isFalse()
    }

    // ── what a reading writes ──

    private val read = DocumentUnderstanding(
        documentType = "invoice_bill", documentTypeConfidence = 0.9f, topics = listOf("tax"), layoutTemplate = "din5008_b",
        title = "Rechnung · Stadtwerke", titleCode = "composed", titleArgs = listOf("invoice_bill", "Stadtwerke", ""),
        summary = "Eine Rechnung.", summarySource = SummarySource.MODEL,
    )

    @Test
    fun `the model's family, topics and confidence replace those it chose before, and the layout template is always the latest`() {
        val before = doc().copy(extractionType = "official_letter", topics = listOf("government"), layoutTemplate = "old")
        val after = ReprocessOverwritePolicy.applyFamily(before, read)
        assertThat(after.extractionType).isEqualTo("invoice_bill")
        assertThat(after.topics).containsExactly("tax")
        assertThat(after.extractionTypeConfidence).isEqualTo(0.9f)
        assertThat(after.familySource).isEqualTo(FamilySource.MODEL)
        assertThat(after.layoutTemplate).isEqualTo("din5008_b")
    }

    @Test
    fun `a family a person chose is never replaced, its topics neither`() {
        val before = doc(family = FamilySource.USER).copy(extractionType = "official_letter", topics = listOf("health"), layoutTemplate = "old")
        val after = ReprocessOverwritePolicy.applyFamily(before, read)
        assertThat(after.extractionType).isEqualTo("official_letter")
        assertThat(after.topics).containsExactly("health")
        assertThat(after.familySource).isEqualTo(FamilySource.USER)
        // The shape of the letter is not a choice.
        assertThat(after.layoutTemplate).isEqualTo("din5008_b")
    }

    @Test
    fun `a family a person forces is stored as the person's choice, whatever was there`() {
        val after = ReprocessOverwritePolicy.applyFamily(doc(), read.copy(documentType = "receipt"), forcedFamily = "receipt")
        assertThat(after.extractionType).isEqualTo("receipt")
        assertThat(after.familySource).isEqualTo(FamilySource.USER)
        assertThat(after.topics).containsExactly("tax")
    }

    @Test
    fun `a reading with no family leaves the document's family alone`() {
        val before = doc().copy(extractionType = "official_letter", topics = listOf("health"))
        val after = ReprocessOverwritePolicy.applyFamily(before, DocumentUnderstanding())
        assertThat(after).isEqualTo(before)
    }

    @Test
    fun `topics scored late are stored only where the family is the model's to set`() {
        val late = DocumentUnderstanding(topics = listOf("tax", "government"))
        assertThat(ReprocessOverwritePolicy.applyLateTopics(doc(), late).topics).containsExactly("tax", "government").inOrder()
        assertThat(ReprocessOverwritePolicy.applyLateTopics(doc(family = FamilySource.USER), late).topics).isEmpty()
        assertThat(ReprocessOverwritePolicy.applyLateTopics(doc().copy(topics = listOf("health")), DocumentUnderstanding()).topics).containsExactly("health")
    }

    @Test
    fun `a first stage with no topics scored keeps the stored topics`() {
        val before = doc().copy(extractionType = "official_letter", topics = listOf("government"))
        val after = ReprocessOverwritePolicy.applyFamily(before, read.copy(topics = emptyList()))
        assertThat(after.extractionType).isEqualTo("invoice_bill")
        assertThat(after.topics).containsExactly("government")
    }

    @Test
    fun `a stored sensitive topic survives a re-read that scores other topics, in both stages`() {
        val before = doc().copy(extractionType = "medical", topics = listOf("health"))
        assertThat(ReprocessOverwritePolicy.applyFamily(before, read.copy(documentType = "medical")).topics).containsExactly("tax", "health")
        assertThat(ReprocessOverwritePolicy.applyLateTopics(before, DocumentUnderstanding(topics = listOf("tax"))).topics).containsExactly("tax", "health")
    }

    @Test
    fun `a stored sensitive family is not replaced by a non-sensitive model family`() {
        val before = doc().copy(extractionType = "medical", extractionTypeConfidence = 0.7f, topics = listOf("health"))
        val after = ReprocessOverwritePolicy.applyFamily(before, read)
        assertThat(after.extractionType).isEqualTo("medical")
        assertThat(after.extractionTypeConfidence).isEqualTo(0.7f)
        assertThat(after.topics).contains("health")
    }

    @Test
    fun `a legacy health type keeps its sensitive family and topic through a re-read`() {
        val before = doc().copy(extractionType = "health", topics = emptyList())
        val after = ReprocessOverwritePolicy.applyFamily(before, read.copy(documentType = "official_letter", topics = emptyList()))
        assertThat(after.extractionType).isEqualTo("medical")
        assertThat(after.topics).containsExactly("health")
    }

    @Test
    fun `a person's forced family drops the sensitivity the person did not choose`() {
        val before = doc().copy(extractionType = "medical", topics = listOf("health"))
        val after = ReprocessOverwritePolicy.applyFamily(before, read, forcedFamily = "invoice_bill")
        assertThat(after.extractionType).isEqualTo("invoice_bill")
        assertThat(after.topics).containsExactly("tax")
    }

    @Test
    fun `a migrated medical letter re-read as an official letter stays out of the all-documents chat`() {
        val migrated = doc().copy(extractionType = "medical", topics = listOf("health"))
        val reread = ReprocessOverwritePolicy.applyFamily(migrated, read.copy(documentType = "official_letter", topics = emptyList()))
        assertThat(ObserveChatVisibleDocumentsUseCase.isChatVisible(reread)).isFalse()
        val late = ReprocessOverwritePolicy.applyLateTopics(reread, DocumentUnderstanding(topics = listOf("government")))
        assertThat(ObserveChatVisibleDocumentsUseCase.isChatVisible(late)).isFalse()
    }

    @Test
    fun `the composed title replaces an app default and an earlier composed title, with its code, args and source`() {
        val default = doc().copy(title = "Scan", titleCode = "scanned_pages", titleArgs = listOf("2"))
        val after = ReprocessOverwritePolicy.applyTitle(default, read)
        assertThat(after.title).isEqualTo("Rechnung · Stadtwerke")
        assertThat(after.titleCode).isEqualTo("composed")
        assertThat(after.titleArgs).containsExactly("invoice_bill", "Stadtwerke", "").inOrder()
        assertThat(after.titleSource).isEqualTo(TitleSource.COMPOSED)
        val again = ReprocessOverwritePolicy.applyTitle(after.copy(titleArgs = listOf("invoice_bill", "Alt", "")), read.copy(titleArgs = listOf("invoice_bill", "Neu", "")))
        assertThat(again.titleArgs[1]).isEqualTo("Neu")
    }

    @Test
    fun `a title a person set and real words an older reading wrote are never touched`() {
        val user = doc().copy(title = "Meine Rechnung", titleCode = null, isUserTitle = true, titleSource = TitleSource.USER)
        assertThat(ReprocessOverwritePolicy.applyTitle(user, read)).isEqualTo(user)
        val legacy = doc().copy(title = "Stromrechnung Stadtwerke", titleCode = null, titleSource = TitleSource.MODEL)
        assertThat(ReprocessOverwritePolicy.applyTitle(legacy, read)).isEqualTo(legacy)
    }

    @Test
    fun `a reading that composed no title changes none`() {
        val default = doc().copy(title = "Scan", titleCode = "scanned_pages", titleArgs = listOf("2"))
        assertThat(ReprocessOverwritePolicy.applyTitle(default, DocumentUnderstanding())).isEqualTo(default)
    }

    @Test
    fun `the summary and its source are written unless a person wrote the stored one`() {
        val after = ReprocessOverwritePolicy.applySummary(doc(), read)
        assertThat(after.summary).isEqualTo("Eine Rechnung.")
        assertThat(after.summarySource).isEqualTo(SummarySource.MODEL)
        assertThat(after.summaryCode).isNull()

        val mine = doc(summary = SummarySource.USER).copy(summary = "Meine Notiz")
        assertThat(ReprocessOverwritePolicy.applySummary(mine, read)).isEqualTo(mine)
    }

    @Test
    fun `a template summary is stored as its code and arguments, with no text`() {
        val template = DocumentUnderstanding(summarySource = SummarySource.TEMPLATE, summaryCode = "template", summaryArgs = listOf("invoice_bill", "Stadtwerke", "", "64,98 €", "", ""))
        val after = ReprocessOverwritePolicy.applySummary(doc(summary = SummarySource.MODEL).copy(summary = "Alt"), template)
        assertThat(after.summary).isNull()
        assertThat(after.summarySource).isEqualTo(SummarySource.TEMPLATE)
        assertThat(after.summaryCode).isEqualTo("template")
        assertThat(after.summaryArgs).hasSize(6)
    }

    @Test
    fun `a reading that wrote no summary leaves the stored one, so it stays pending when there is none`() {
        val pending = doc()
        assertThat(ReprocessOverwritePolicy.applySummary(pending, DocumentUnderstanding())).isEqualTo(pending)
        val kept = doc(summary = SummarySource.MODEL).copy(summary = "Alt")
        assertThat(ReprocessOverwritePolicy.applySummary(kept, DocumentUnderstanding())).isEqualTo(kept)
    }

    @Test
    fun `the actions a second stage chose replace the stored ones, an empty list clears them`() {
        val before = doc().copy(actionItems = listOf(ActionItem("reply")))
        assertThat(ReprocessOverwritePolicy.applyActions(before, DocumentUnderstanding(actionItems = listOf(ActionItem("pay", mapOf("date" to "due_date"))))).actionItems)
            .containsExactly(ActionItem("pay", mapOf("date" to "due_date")))
        assertThat(ReprocessOverwritePolicy.applyActions(before, DocumentUnderstanding(actionItems = emptyList())).actionItems).isEmpty()
    }

    @Test
    fun `a reading that scored no actions leaves the stored ones`() {
        val before = doc().copy(actionItems = listOf(ActionItem("reply")))
        assertThat(ReprocessOverwritePolicy.applyActions(before, DocumentUnderstanding())).isEqualTo(before)
    }
}
