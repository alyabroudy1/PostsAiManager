package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.document.ReprocessOverwritePolicy
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.LanguageSource
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.ValueSource
import org.junit.jupiter.api.Test

/** What a date or an amount means, chosen by the user, and the language the user set: both survive a re-read. */
class UserMeaningTest {

    private val merge = MergeExtractionUseCase()
    private var counter = 0
    private val newId: (String) -> String = { "rev${counter++}" }

    private fun date(value: String, role: String?, source: ValueSource = ValueSource.MACHINE) = ExtractedData(
        id = "id-$value", documentId = "d1", fieldName = "Due date", fieldValue = value, fieldType = ExtractedFieldType.DATE, confidence = 0.6f,
        slotKey = "due_date", role = role, source = source, machineValue = value, machineConfidence = 0.6f,
    )

    private val appointment = ValueMeanings.role(ValueMeanings.DEFAULT.byId("APPOINTMENT")!!)
    private val dueDate = ValueMeanings.role(ValueMeanings.DEFAULT.byId("DUE_DATE")!!)

    @Test
    fun `choosing a meaning makes the row the person's, confirmed, with the value untouched`() {
        val chosen = merge.applyUserMeaning(date("15.10.2026", dueDate), appointment, now = 5L)

        assertThat(chosen.role).isEqualTo(appointment)
        assertThat(chosen.fieldValue).isEqualTo("15.10.2026")
        assertThat(chosen.source).isEqualTo(ValueSource.USER)
        assertThat(chosen.reviewState).isEqualTo(ReviewState.CONFIRMED)
        assertThat(chosen.flagsMatchReviewState).isTrue()
        assertThat(chosen.needsReview).isFalse()
    }

    @Test
    fun `choosing none of the meanings clears the role and keeps the choice`() {
        val chosen = merge.applyUserMeaning(date("15.10.2026", dueDate), role = null, now = 5L)

        assertThat(chosen.role).isNull()
        assertThat(chosen.reviewState).isEqualTo(ReviewState.CONFIRMED)
    }

    @Test
    fun `a re-read keeps the chosen meaning and flags a differing value instead of applying it`() {
        val chosen = merge.applyUserMeaning(date("15.10.2026", dueDate), appointment, now = 5L)

        // The new reading finds another date and the old meaning again.
        val outcome = merge(listOf(chosen), listOf(date("16.10.2026", dueDate).copy(id = "fresh")), "v2", now = 9L, newId = newId)

        val kept = outcome.toPersist.single()
        assertThat(kept.role).isEqualTo(appointment)
        assertThat(kept.fieldValue).isEqualTo("15.10.2026")
        assertThat(kept.hasUnreviewedMachineChange).isTrue()
    }

    @Test
    fun `a re-read that agrees leaves the chosen meaning alone`() {
        val chosen = merge.applyUserMeaning(date("15.10.2026", dueDate), appointment, now = 5L)

        val outcome = merge(listOf(chosen), listOf(date("15.10.2026", dueDate).copy(id = "fresh")), "v2", now = 9L, newId = newId)

        assertThat(outcome.toPersist.single().role).isEqualTo(appointment)
        assertThat(outcome.toPersist.single().hasUnreviewedMachineChange).isFalse()
    }

    @Test
    fun `an ignored row stays ignored when its meaning is set`() {
        val ignored = merge.applyUserDelete(date("15.10.2026", dueDate), now = 1L)

        val chosen = merge.applyUserMeaning(ignored, appointment, now = 5L)

        assertThat(chosen.reviewState).isEqualTo(ReviewState.IGNORED)
    }

    // ── the language ──

    private fun document(language: String?, source: LanguageSource) = Document(
        id = "d1", title = "t", sourceType = SourceType.CAMERA, createdAt = 1, modifiedAt = 1, language = language, languageSource = source,
    )

    @Test
    fun `a reading replaces a language the model found, but never one the user set`() {
        assertThat(ReprocessOverwritePolicy.applyLanguage(document("de", LanguageSource.MODEL), "en").language).isEqualTo("en")
        assertThat(ReprocessOverwritePolicy.applyLanguage(document("ar", LanguageSource.USER), "en").language).isEqualTo("ar")
    }

    @Test
    fun `a reading that found no language leaves the stored one`() {
        assertThat(ReprocessOverwritePolicy.applyLanguage(document("de", LanguageSource.MODEL), null).language).isEqualTo("de")
        assertThat(ReprocessOverwritePolicy.applyLanguage(document("de", LanguageSource.MODEL), " ").language).isEqualTo("de")
    }
}
