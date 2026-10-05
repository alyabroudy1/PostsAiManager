package com.postsaimanager.feature.documents

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.text.SummaryWriter
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.SummarySource
import org.junit.jupiter.api.Test

class PagesSummaryPresenterTest {

    private var n = 0

    private fun field(slotKey: String, value: String, deleted: Boolean = false) = ExtractedData(
        id = "f${n++}", documentId = "d", fieldName = slotKey, fieldValue = value, fieldType = ExtractedFieldType.OTHER,
        confidence = 0.9f, slotKey = slotKey, deletedByUser = deleted,
    )

    private fun doc(
        status: DocumentStatus = DocumentStatus.EXTRACTED,
        summary: String? = null,
        summaryCode: String? = null,
        summaryArgs: List<String> = emptyList(),
    ) = Document(
        id = "d", title = "T", sourceType = SourceType.CAMERA, createdAt = 0, modifiedAt = 0,
        status = status, summary = summary, summaryCode = summaryCode, summaryArgs = summaryArgs,
    )

    private val sender = field(UnderstandingToFields.SLOT_SENDER, "Jobcenter")
    private fun addressee(name: String) = field(UnderstandingToFields.SLOT_ADDRESSEE, name)
    private val ai = PagesContext(aiInstalled = true, selfName = "Mo Ali")

    @Test
    fun `a read document shows the summary, the sender and the recipient`() {
        val card = PagesSummaryPresenter.present(doc(summary = "A letter about benefits."), listOf(sender, addressee("Someone Else")), ai)

        assertThat(card.state).isEqualTo(PagesSummaryState.READY)
        assertThat(card.summaryText).isEqualTo("A letter about benefits.")
        assertThat(card.summarySource).isEqualTo(SummarySource.MODEL)
        assertThat(card.from).isEqualTo("Jobcenter")
        assertThat(card.to).isEqualTo(PagesRecipient.Named("Someone Else"))
    }

    @Test
    fun `the recipient is You only on an exact folded match with the Me profile`() {
        fun to(name: String, self: String? = "Mo Ali") =
            PagesSummaryPresenter.present(doc(), listOf(addressee(name)), PagesContext(true, self)).to

        assertThat(to("  mo   ALI ")).isEqualTo(PagesRecipient.You)
        assertThat(to("Mö Alí")).isEqualTo(PagesRecipient.You)
        assertThat(to("Mo Ali Khan")).isEqualTo(PagesRecipient.Named("Mo Ali Khan"))
        assertThat(to("Mo")).isEqualTo(PagesRecipient.Named("Mo"))
        assertThat(to("Mo Ali", self = null)).isEqualTo(PagesRecipient.Named("Mo Ali"))
        assertThat(to("Mo Ali", self = " ")).isEqualTo(PagesRecipient.Named("Mo Ali"))
    }

    @Test
    fun `lines with no data are omitted`() {
        val card = PagesSummaryPresenter.present(doc(summary = "Text."), listOf(field(UnderstandingToFields.SLOT_SENDER, "  ")), ai)

        assertThat(card.from).isNull()
        assertThat(card.to).isNull()
    }

    @Test
    fun `an ignored sender is not shown`() {
        val card = PagesSummaryPresenter.present(doc(summary = "Text."), listOf(field(UnderstandingToFields.SLOT_SENDER, "X", deleted = true)), ai)

        assertThat(card.from).isNull()
    }

    @Test
    fun `a template summary is kept as its args with the fields badge source`() {
        val card = PagesSummaryPresenter.present(
            doc(summaryCode = SummaryWriter.TEMPLATE_CODE, summaryArgs = listOf("bill", "Jobcenter")), emptyList(), ai,
        )

        assertThat(card.summaryText).isNull()
        assertThat(card.templateArgs).containsExactly("bill", "Jobcenter").inOrder()
        assertThat(card.summarySource).isEqualTo(SummarySource.TEMPLATE)
    }

    @Test
    fun `nothing read yet while the AI works is the reading state`() {
        assertThat(PagesSummaryPresenter.present(doc(DocumentStatus.PROCESSING), emptyList(), ai).state).isEqualTo(PagesSummaryState.READING)
        assertThat(PagesSummaryPresenter.present(doc(DocumentStatus.QUEUED), emptyList(), ai).state).isEqualTo(PagesSummaryState.READING)
        assertThat(PagesSummaryPresenter.present(doc(), emptyList(), ai, summaryComing = true).state).isEqualTo(PagesSummaryState.READING)
    }

    @Test
    fun `no model and nothing read offers the install`() {
        val card = PagesSummaryPresenter.present(doc(DocumentStatus.QUEUED), emptyList(), PagesContext(aiInstalled = false))

        assertThat(card.state).isEqualTo(PagesSummaryState.AI_NOT_INSTALLED)
    }

    @Test
    fun `no model but something already read still shows it`() {
        val card = PagesSummaryPresenter.present(doc(summary = "Text."), emptyList(), PagesContext(aiInstalled = false))

        assertThat(card.state).isEqualTo(PagesSummaryState.READY)
    }

    @Test
    fun `a failed document is the failed state`() {
        val card = PagesSummaryPresenter.present(doc(DocumentStatus.FAILED), emptyList(), PagesContext(aiInstalled = false))

        assertThat(card.state).isEqualTo(PagesSummaryState.FAILED)
    }
}
