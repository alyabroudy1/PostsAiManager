package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import com.postsaimanager.core.domain.extraction.zones.ValueMeaningReader
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ValueMeaningsTest {

    private val registry = ValueMeanings.DEFAULT

    @Test
    fun `the registry lists the meanings of dates and of amounts, each with a description for the question`() {
        assertThat(registry.of(MeaningKind.DATE).map { it.id }).containsExactly(
            "DUE_DATE", "APPOINTMENT", "DEADLINE", "PERIOD_START", "PERIOD_END", "LETTER_DATE", "BIRTH_DATE",
        ).inOrder()
        assertThat(registry.of(MeaningKind.AMOUNT).map { it.id }).containsExactly("TOTAL_DUE", "CREDIT", "PREMIUM", "INVOICE_TOTAL", "FEE").inOrder()
        for (m in registry.all) assertThat(m.description).isNotEmpty()
        // "Other" is the answer when none counts: it is no entry.
        assertThat(registry.byId("OTHER")).isNull()
    }

    @Test
    fun `a meaning is stored as a role in a namespace that a slot's own role is never in`() {
        val appointment = registry.byId("APPOINTMENT")!!
        assertThat(ValueMeanings.role(appointment)).isEqualTo("meaning:APPOINTMENT")
        assertThat(ValueMeanings.fromRole("meaning:APPOINTMENT")).isEqualTo(appointment)
        // The roles slots and parties have are no meaning, even where an id is the same word.
        assertThat(ValueMeanings.fromRole("DUE_DATE")).isNull()
        assertThat(ValueMeanings.fromRole("SENDER")).isNull()
        assertThat(ValueMeanings.fromRole("meaning:ASTROLOGY")).isNull()
        assertThat(ValueMeanings.fromRole(null)).isNull()
    }

    @Test
    fun `only the slots that hold a date or an amount have a meaning kind`() {
        assertThat(MeaningKind.of(SlotKind.DATE)).isEqualTo(MeaningKind.DATE)
        assertThat(MeaningKind.of(SlotKind.DEADLINE)).isEqualTo(MeaningKind.DATE)
        assertThat(MeaningKind.of(SlotKind.AMOUNT)).isEqualTo(MeaningKind.AMOUNT)
        assertThat(MeaningKind.of(SlotKind.REFERENCE)).isNull()
        assertThat(MeaningKind.of(SlotKind.IBAN)).isNull()
        assertThat(MeaningKind.of(SlotKind.NAME)).isNull()
    }

    private val target = ValueMeaningReader.Target("due_date", MeaningKind.DATE, "30.11.2026", null, "BLOCK\n")

    /** Scores the statements of a batch in registry order; the baseline batch (about the made-up date) with [baseline]. */
    private fun reader(profile: ScoringProfile, calls: MutableList<String>, scores: List<Double>, baseline: List<Double>) = ValueMeaningReader(
        score = { name, _, questions ->
            calls += name
            assertThat(questions).hasSize(registry.of(MeaningKind.DATE).size)
            if (name.startsWith("baseline")) baseline else scores
        },
        profile = profile,
    )

    @Test
    fun `the meaning that beats its baseline by the most is the answer, one batch per value and one baseline per block`() {
        val calls = mutableListOf<String>()
        val flat = List(7) { 0.0 }
        // APPOINTMENT (index 1) leads by 3, DEADLINE (index 2) by 1.
        val scores = listOf(0.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        val answers = runBlocking { reader(ScoringProfile(), calls, scores, flat).read(listOf(target, target)) }
        assertThat(answers.getValue("due_date").id).isEqualTo("APPOINTMENT")
        // Two values over one block share the baseline: 1 baseline + 2 meaning batches.
        assertThat(calls).containsExactly("baseline:meaning:date", "meaning:date", "meaning:date")
    }

    @Test
    fun `a gap that is not above the margin is other, and so is a model that cannot score`() {
        val flat = List(7) { 0.0 }
        val scores = listOf(0.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        assertThat(runBlocking { reader(ScoringProfile(defaultMeaningMargin = 3.0), mutableListOf(), scores, flat).read(listOf(target)) }).isEmpty()
        // A per-meaning margin only holds that meaning back: the next best that clears its own margin answers.
        val perMeaning = ScoringProfile(meaningMargins = mapOf("APPOINTMENT" to 5.0))
        assertThat(runBlocking { reader(perMeaning, mutableListOf(), scores, flat).read(listOf(target)) }.getValue("due_date").id).isEqualTo("DEADLINE")
        // The same scores as the made-up date's: nothing is said about this one.
        assertThat(runBlocking { reader(ScoringProfile(), mutableListOf(), scores, scores).read(listOf(target)) }).isEmpty()
        val failing = ValueMeaningReader({ _, _, _ -> null }, ScoringProfile())
        assertThat(runBlocking { failing.read(listOf(target)) }).isEmpty()
    }
}
