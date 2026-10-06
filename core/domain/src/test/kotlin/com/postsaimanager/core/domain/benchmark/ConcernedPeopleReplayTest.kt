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
 * Replays the phone's recordings of "who is this letter for or about?" (`benchmark/concerned/<key>.concerned.json`, recorded by
 * `ConcernedPeopleBenchmarkTest` with the list question in both orders and the per-member scores) through the shipped profile, and
 * pins what the shipped setting names on each letter. The recordings are the model's raw answers; nothing is decided by words.
 */
class ConcernedPeopleReplayTest {

    private class Recording(val key: String, val asked: Set<String>, val expected: Set<String>, val forward: Set<String>, val reversed: Set<String>, val scores: Map<String, Double>)

    private val keys = listOf("N3-schule-familie-2p", "N5-co-familie-1p", "N10-kinderarzt-termin-1p", "N9-fuzzy-name-1p", "N2-kfz-verlaengerung-2p")

    private fun JsonObject.ids(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun load(key: String): Recording {
        val text = checkNotNull(javaClass.getResource("/benchmark/concerned/$key.concerned.json")) { "missing recording $key" }.readText()
        val o = Json.parseToJsonElement(text).jsonObject
        return Recording(
            key, o.ids("asked"), o.ids("expected"), o.ids("forward"), o.ids("reversed"),
            o.getValue("scores").jsonObject.mapValues { it.value.jsonPrimitive.doubleOrNull ?: Double.NaN },
        )
    }

    private val recordings = keys.map(::load)
    private val shipped = ConcernedPeopleProfile()

    @Test
    fun `the shipped setting asks again in the reversed order and keeps the members named both times`() {
        assertThat(shipped.reversedCheck).isTrue()
        val named = recordings.associate { it.key to shipped.keep(it.forward, it.reversed) }
        assertThat(named).containsExactly(
            "N3-schule-familie-2p", setOf("lea"),
            "N5-co-familie-1p", setOf("me"),
            "N10-kinderarzt-termin-1p", setOf("adam", "lea"),
            "N9-fuzzy-name-1p", setOf("me"),
            "N2-kfz-verlaengerung-2p", setOf("me", "max"),
        )
    }

    @Test
    fun `nobody the model was not asked about is ever named, the distractor included`() {
        for (r in recordings) {
            assertThat(shipped.keep(r.forward, r.reversed) - r.asked).isEmpty()
            assertThat(r.forward - r.asked).isEmpty()
            assertThat(r.reversed - r.asked).isEmpty()
        }
    }

    @Test
    fun `the reversed check names fewer wrong people than the forward list alone, and finds the same ones`() {
        fun wrong(named: (Recording) -> Set<String>) = recordings.sumOf { (named(it) - it.expected).size }
        fun found(named: (Recording) -> Set<String>) = recordings.sumOf { (named(it) intersect it.expected).size }
        assertThat(wrong { it.forward }).isEqualTo(4)
        assertThat(wrong { shipped.keep(it.forward, it.reversed) }).isEqualTo(2)
        assertThat(found { it.forward }).isEqualTo(5)
        assertThat(found { shipped.keep(it.forward, it.reversed) }).isEqualTo(5)
    }

    @Test
    fun `a margin of the per-member score over the distractor's, the other question form, on the same letters`() {
        // The distractor ("far") is scored, never listed. Recorded for comparison only: the shipped form is the list.
        fun named(margin: Double) = recordings.associate { r ->
            val base = r.scores.getValue("far")
            r.key to r.asked.filter { r.scores.getValue(it) - base >= margin }.toSet()
        }
        fun wrong(m: Double) = recordings.sumOf { (named(m).getValue(it.key) - it.expected).size }
        fun found(m: Double) = recordings.sumOf { (named(m).getValue(it.key) intersect it.expected).size }
        assertThat(found(2.0)).isEqualTo(5)
        assertThat(wrong(2.0)).isEqualTo(0)
        assertThat(found(0.8)).isEqualTo(7)
        assertThat(wrong(0.8)).isEqualTo(1)
    }
}
