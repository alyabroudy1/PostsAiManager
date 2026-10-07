package com.postsaimanager.core.domain.timeline

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class EventKindReaderTest {

    private val kinds = EventKinds.DEFAULT
    private val batches = mutableListOf<Pair<String, List<String>>>()

    /** Scores each scored kind with [byId] (default [fallback]) and records the batch. */
    private fun reader(profile: EventKindProfile = EventKindProfile(), fallback: Double = -3.0, byId: Map<String, Double> = emptyMap()) =
        EventKindReader(
            scorer = { name, questions ->
                batches += name to questions
                kinds.scored.map { byId[it.id] ?: fallback }
            },
            profile = profile,
        )

    @Test
    fun `information when nothing passes`() = runTest {
        val reading = reader(fallback = -2.0).read()!!
        assertThat(reading.kindId).isEqualTo(EventKinds.INFORMATION)
    }

    @Test
    fun `the best kind above the baseline wins`() = runTest {
        val reading = reader(byId = mapOf(EventKinds.APPROVAL to 1.5, EventKinds.PAYMENT_DEMAND to 0.4)).read()!!
        assertThat(reading.kindId).isEqualTo(EventKinds.APPROVAL)
    }

    @Test
    fun `a kind is measured against its content-free baseline, and must beat the margin`() = runTest {
        // The model leans Yes on rejection by 2.0 over an empty letter, so a raw 2.5 is only 0.5 above its habit.
        val profile = EventKindProfile(kindBias = mapOf(EventKinds.REJECTION to 2.0), margin = 1.0)
        assertThat(reader(profile, byId = mapOf(EventKinds.REJECTION to 2.5)).read()!!.kindId).isEqualTo(EventKinds.INFORMATION)
        assertThat(reader(profile, byId = mapOf(EventKinds.REJECTION to 3.5)).read()!!.kindId).isEqualTo(EventKinds.REJECTION)
    }

    @Test
    fun `the unfitted profile has margin 0 and no bias`() {
        val profile = EventKindProfile()
        assertThat(profile.margin).isEqualTo(0.0)
        assertThat(profile.kindBias).isEmpty()
    }

    @Test
    fun `exactly the margin does not pass`() = runTest {
        assertThat(reader(byId = mapOf(EventKinds.APPOINTMENT to 0.0)).read()!!.kindId).isEqualTo(EventKinds.INFORMATION)
    }

    @Test
    fun `one batch asks every scored kind, with the category as context and a marker`() = runTest {
        reader().read("a letter that decides on an application")
        val (name, questions) = batches.single()
        assertThat(name).isEqualTo(EventKindReader.BATCH)
        assertThat(questions).hasSize(kinds.scored.size)
        assertThat(questions).containsNoDuplicates()
        assertThat(questions.all(EventQuestions::isEventQuestion)).isTrue()
        assertThat(questions.first()).startsWith("This document is: a letter that decides on an application. ")
    }

    @Test
    fun `the fallback and the kinds code writes are never scored`() {
        val ids = kinds.scored.map { it.id }
        assertThat(ids).containsNoneOf(EventKinds.INFORMATION, EventKinds.DEADLINE_PASSED, EventKinds.REMINDER_SET, EventKinds.EMAIL_SENT, EventKinds.CALENDAR_ENTRY)
        assertThat(ids).hasSize(11)
    }

    @Test
    fun `a failed batch decides nothing`() = runTest {
        assertThat(EventKindReader({ _, _ -> null }).read()).isNull()
        assertThat(EventKindReader({ _, _ -> listOf(1.0) }).read()).isNull()
    }

    @Test
    fun `every kind has English, German and Arabic labels, and an unknown language reads English`() {
        for (kind in kinds.all) {
            assertThat(kind.labels.keys).containsAtLeast("en", "de", "ar")
            assertThat(kind.label("de-AT")).isEqualTo(kind.labels.getValue("de"))
            assertThat(kind.label("fr")).isEqualTo(kind.labels.getValue("en"))
            assertThat(kind.label(null)).isEqualTo(kind.labels.getValue("en"))
        }
    }

    @Test
    fun `an unknown id is information`() {
        assertThat(kinds.byId("no-such-kind").id).isEqualTo(EventKinds.INFORMATION)
        assertThat(kinds.knows("approval")).isTrue()
    }
}
