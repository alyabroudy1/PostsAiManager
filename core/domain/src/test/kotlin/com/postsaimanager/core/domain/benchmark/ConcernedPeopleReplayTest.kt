package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.document.people.ConcernedPeopleProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Replays the phone's recordings of "who is this letter for or about?" (`benchmark/concerned/<key>.concerned.json`: the per-member
 * log-odds of the five benchmark letters and of the distractor name "far", which the letter does not mention) through the shipped
 * profile and pins what it names. The recordings are the model's raw scores; nothing is decided by words.
 */
class ConcernedPeopleReplayTest {

    private class Recording(val key: String, val asked: List<String>, val expected: Set<String>, val scores: Map<String, Double>) {
        val baseline: Double get() = scores.getValue("far")
        fun named(profile: ConcernedPeopleProfile): Set<String> =
            profile.select(asked.map { scores.getValue(it) }, baseline).map { asked[it] }.toSet()
    }

    private val keys = listOf("N3-schule-familie-2p", "N5-co-familie-1p", "N10-kinderarzt-termin-1p", "N9-fuzzy-name-1p", "N2-kfz-verlaengerung-2p")

    private fun JsonObject.ids(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.content }

    private fun load(key: String): Recording {
        val text = checkNotNull(javaClass.getResource("/benchmark/concerned/$key.concerned.json")) { "missing recording $key" }.readText()
        val o = Json.parseToJsonElement(text).jsonObject
        return Recording(
            key, o.ids("asked"), o.ids("expected").toSet(),
            o.getValue("scores").jsonObject.mapValues { it.value.jsonPrimitive.doubleOrNull ?: Double.NaN },
        )
    }

    private val recordings = keys.map(::load)
    private val shipped = ConcernedPeopleProfile()

    private fun found(profile: ConcernedPeopleProfile) = recordings.sumOf { (it.named(profile) intersect it.expected).size }
    private fun wrong(profile: ConcernedPeopleProfile) = recordings.sumOf { (it.named(profile) - it.expected).size }

    @Test
    fun `the shipped margin names these people on each recorded letter`() {
        assertThat(recordings.associate { it.key to it.named(shipped) }).containsExactly(
            "N3-schule-familie-2p", setOf("me", "lea"),
            "N5-co-familie-1p", setOf("me", "jonas"),
            "N10-kinderarzt-termin-1p", setOf("adam"),
            "N9-fuzzy-name-1p", setOf("me"),
            "N2-kfz-verlaengerung-2p", setOf("me", "max"),
        )
        assertThat(found(shipped)).isEqualTo(7) // of 8 expected
        assertThat(wrong(shipped)).isEqualTo(1) // me on N5, which only shares the c/o surname
    }

    @Test
    fun `the margin sits in the middle of a plateau that runs from about 0_52 to 0_85`() {
        for (margin in listOf(0.55, 0.6, 0.7, 0.8, 0.85)) {
            val profile = ConcernedPeopleProfile(margin = margin)
            assertThat(found(profile)).isEqualTo(7)
            assertThat(wrong(profile)).isEqualTo(1)
        }
        // Below the plateau an unexpected family member comes in; above it a real one drops out.
        assertThat(wrong(ConcernedPeopleProfile(margin = 0.5))).isEqualTo(2)
        assertThat(found(ConcernedPeopleProfile(margin = 0.86))).isEqualTo(6)
    }

    @Test
    fun `nobody the model was not asked about is ever named, the distractor included`() {
        for (r in recordings) assertThat(r.named(shipped) - r.asked.toSet()).isEmpty()
    }
}
