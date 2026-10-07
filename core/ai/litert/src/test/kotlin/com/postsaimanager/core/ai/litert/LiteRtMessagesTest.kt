package com.postsaimanager.core.ai.litert

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ToolExchange
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class LiteRtMessagesTest {

    private val skillText = "---\nname: schedule-reminder\ndescription: d\n---\n\n" + "Rule. ".repeat(230)

    private fun json(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }).toString()

    private val loadSkill = ToolExchange(
        "load_skill",
        json("skill_name" to "schedule-reminder"),
        json("skill_name" to "schedule-reminder", "skill_instructions" to skillText),
    )

    @Test
    fun `a replayed load_skill response is a short stub, the skill name kept`() {
        val replayed = LiteRtMessages.replayedResult(loadSkill)

        assertThat(replayed["skill_name"]).isEqualTo("schedule-reminder")
        assertThat(replayed["skill_instructions"])
            .isEqualTo("(instructions of schedule-reminder were loaded earlier; call load_skill again to read them)")
    }

    @Test
    fun `a history with one earlier load_skill prefills far fewer characters on a rebuild`() {
        val before = loadSkill.resultJson.length
        val after = LiteRtMessages.replayedResult(loadSkill).values.sumOf { it.length }

        println("replayed load_skill response: $before chars stored, $after chars replayed")
        assertThat(after).isLessThan(before / 5)
    }

    @Test
    fun `run_intent results are replayed as stored`() {
        val intent = ToolExchange(
            "run_intent",
            json("intent" to "schedule_notification"),
            json("status" to "Not done yet: shown as a card"),
        )

        assertThat(LiteRtMessages.replayedResult(intent)).containsExactly("status", "Not done yet: shown as a card")
    }

    @Test
    fun `the stored exchange keeps the full text`() {
        LiteRtMessages.replayedResult(loadSkill)

        assertThat(loadSkill.resultJson).contains(skillText.take(40).replace("\n", "\\n"))
    }
}
