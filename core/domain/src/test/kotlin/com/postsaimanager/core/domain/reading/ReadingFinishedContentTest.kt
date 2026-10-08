package com.postsaimanager.core.domain.reading

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.DocumentTitleCodes
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.SourceType
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** What a "Letter understood" notification may say: the highlight, the privacy rules, and the grouped content. */
class ReadingFinishedContentTest {

    private fun doc(
        id: String = "d1",
        type: String? = "invoice_bill",
        topics: List<String> = emptyList(),
        concerned: List<String>? = emptyList(),
        actions: List<ActionItem> = listOf(ActionItem("pay", mapOf("amount" to "total", "date" to "due_date"))),
    ) = Document(
        id = id, title = "Rechnung Juli", status = DocumentStatus.EXTRACTED, sourceType = SourceType.CAMERA, createdAt = 0L, modifiedAt = 0L,
        extractionType = type, topics = topics, concernedProfileIds = concerned, actionItems = actions,
        titleCode = DocumentTitleCodes.COMPOSED, titleArgs = listOf("invoice_bill", "Stadtwerke", "Rechnung Juli"),
    )

    private fun field(slot: String, value: String) = ExtractedData(
        id = "f-$slot", documentId = "d1", fieldName = slot, fieldValue = value, fieldType = ExtractedFieldType.TEXT, confidence = 0.9f, slotKey = slot,
    )

    private val fields = listOf(field("total", "104,20 €"), field("due_date", "15.10.2026"))

    private fun person(id: String, sensitive: Boolean) =
        Profile(id = id, householdRole = HouseholdRole.MEMBER, name = id, sensitive = sensitive, createdAt = 0L, modifiedAt = 0L)

    // ── the highlight ──

    @Test
    fun `the highlight is the date and amount of the first action that states one`() {
        val highlight = ReadingHighlight.of(doc(), fields)!!
        assertThat(highlight.date).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(highlight.amount).isEqualTo("104,20 €")
        assertThat(highlight.isDeadline).isTrue()
    }

    @Test
    fun `a letter with no action has no highlight`() {
        assertThat(ReadingHighlight.of(doc(actions = emptyList()), fields)).isNull()
    }

    @Test
    fun `an appointment is not a deadline`() {
        val highlight = ReadingHighlight.of(doc(actions = listOf(ActionItem("attend", mapOf("date" to "due_date")))), fields)!!
        assertThat(highlight.isDeadline).isFalse()
    }

    // ── the letter ──

    @Test
    fun `a letter is named by its sender and subject, not by the type nor by a default title`() {
        assertThat(UnderstoodLetter.of(doc(), fields, emptyList()).title).isEqualTo("Stadtwerke · Rechnung Juli")
        val unnamed = doc().copy(titleCode = DocumentTitleCodes.SCANNED_PAGES, titleArgs = listOf("1"), title = "Scanned 1 page")
        assertThat(UnderstoodLetter.of(unnamed, fields, emptyList()).title).isNull()
    }

    // ── privacy ──

    @Test
    fun `a health letter is private by its family, its topic and its legacy type`() {
        assertThat(UnderstoodLetter.of(doc(type = "medical"), fields, emptyList()).private).isTrue()
        assertThat(UnderstoodLetter.of(doc(topics = listOf("health")), fields, emptyList()).private).isTrue()
        assertThat(UnderstoodLetter.of(doc(type = "health"), fields, emptyList()).private).isTrue()
        assertThat(UnderstoodLetter.of(doc(), fields, emptyList()).private).isFalse()
    }

    @Test
    fun `a letter for a sensitive person is private, and so is one whose people are not decided yet`() {
        val people = listOf(person("maria", sensitive = true), person("amir", sensitive = false))
        assertThat(UnderstoodLetter.of(doc(concerned = listOf("maria")), fields, people).private).isTrue()
        assertThat(UnderstoodLetter.of(doc(concerned = listOf("amir")), fields, people).private).isFalse()
        assertThat(UnderstoodLetter.of(doc(concerned = emptyList()), fields, people).private).isFalse()
        // Not decided yet: it may concern the sensitive person.
        assertThat(UnderstoodLetter.of(doc(concerned = null), fields, people).private).isTrue()
        // Nobody is sensitive: an undecided letter is an ordinary one.
        assertThat(UnderstoodLetter.of(doc(concerned = null), fields, listOf(person("amir", sensitive = false))).private).isFalse()
    }

    @Test
    fun `one ordinary letter is named with its highlight`() {
        val letter = UnderstoodLetter.of(doc(), fields, emptyList())
        val content = ReadingFinishedContent.of(listOf(letter), hideContent = false)
        assertThat(content.count).isEqualTo(1)
        assertThat(content.documentId).isEqualTo("d1")
        assertThat(content.title).isEqualTo("Stadtwerke · Rechnung Juli")
        assertThat(content.highlight?.amount).isEqualTo("104,20 €")
    }

    @Test
    fun `a private letter carries no content at all, only the document to open`() {
        val letter = UnderstoodLetter.of(doc(type = "medical"), fields, emptyList())
        val content = ReadingFinishedContent.of(listOf(letter), hideContent = false)
        assertThat(content.documentId).isEqualTo("d1")
        assertThat(content.title).isNull()
        assertThat(content.highlight).isNull()
    }

    @Test
    fun `with the app lock on no letter carries any content`() {
        val letter = UnderstoodLetter.of(doc(), fields, emptyList())
        val one = ReadingFinishedContent.of(listOf(letter), hideContent = true)
        assertThat(one.title).isNull()
        assertThat(one.highlight).isNull()
        val several = ReadingFinishedContent.of(listOf(letter, letter.copy(documentId = "d2")), hideContent = true)
        assertThat(several.lines).isEmpty()
        assertThat(several.hiddenCount).isEqualTo(2)
    }

    // ── groups ──

    @Test
    fun `several letters make one group that lists the letters that may be named and counts the rest`() {
        val ordinary = UnderstoodLetter.of(doc(id = "d1"), fields, emptyList())
        val health = UnderstoodLetter.of(doc(id = "d2", type = "medical"), fields, emptyList())
        val other = UnderstoodLetter.of(doc(id = "d3"), fields, emptyList()).copy(title = "Finanzamt · Bescheid")
        val content = ReadingFinishedContent.of(listOf(ordinary, health, other), hideContent = false)

        assertThat(content.count).isEqualTo(3)
        assertThat(content.documentId).isNull()
        assertThat(content.lines.map { it.title }).containsExactly("Stadtwerke · Rechnung Juli", "Finanzamt · Bescheid").inOrder()
        assertThat(content.hiddenCount).isEqualTo(1)
        // The health letter's words are nowhere in the content.
        assertThat(content.toString()).doesNotContain("medical")
    }

    @Test
    fun `a long group lists a few titles and counts the others`() {
        val letters = (1..8).map { UnderstoodLetter.of(doc(id = "d$it"), fields, emptyList()).copy(title = "Letter $it") }
        val content = ReadingFinishedContent.of(letters, hideContent = false)
        assertThat(content.lines).hasSize(ReadingFinishedContent.MAX_LINES)
        assertThat(content.hiddenCount).isEqualTo(8 - ReadingFinishedContent.MAX_LINES)
    }
}
