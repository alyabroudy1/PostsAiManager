package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.postsaimanager.core.domain.document.list.PartyNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Keeps the concerned-people evaluation's spec honest (`benchmark/concerned-people-spec.json`, recorded on the phone by
 * `ConcernedPeopleBenchmarkTest`): every letter's fixture exists, every profile that is not a distractor passes the name-token
 * pre-filter (so the model is asked about it), every distractor fails it (so it can never be named), and the expected people are
 * profiles of the letter. The model's answers are not tested here: they need the phone.
 */
class ConcernedPeopleSpecTest {

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResource(path)) { "missing $path" }.readText()

    @Test
    fun `the spec's profiles agree with the name-token pre-filter on every letter`() {
        val letters = Json.parseToJsonElement(resource("/benchmark/concerned-people-spec.json")).jsonObject.getValue("letters").jsonArray
        assertThat(letters.size).isAtLeast(4)
        for (letter in letters.map { it.jsonObject }) {
            val key = letter.getValue("key").jsonPrimitive.content
            val fixture = BenchmarkFixtures.parseFixture(resource("/benchmark/fixtures/$key.json"))
            val tokens = PartyNames.tokenSet(fixture.pages.flatMap { it.blocks }.joinToString("\n") { it.text })
            val profiles = letter.getValue("profiles").jsonArray.map { it.jsonObject }
            for (p in profiles) {
                val name = p.getValue("name").jsonPrimitive.content
                val distractor = p["distractor"]?.jsonPrimitive?.content == "true"
                assertWithMessage("$key: '$name' mentioned").that(PartyNames.mentions(tokens, name)).isEqualTo(!distractor)
            }
            val ids = profiles.map { it.getValue("id").jsonPrimitive.content }.toSet()
            val expected = letter.getValue("expected").jsonArray.map { it.jsonPrimitive.content }
            assertThat(ids).containsAtLeastElementsIn(expected)
            assertWithMessage("$key: one Me").that(profiles.count { it["self"]?.jsonPrimitive?.content == "true" }).isEqualTo(1)
        }
    }
}
