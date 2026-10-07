package com.postsaimanager.agent

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.skills.AgentActionParser
import com.postsaimanager.core.domain.skills.AgentIntent
import com.postsaimanager.core.domain.skills.ActionParse
import com.postsaimanager.core.domain.skills.Skill
import com.postsaimanager.core.domain.skills.SkillParseResult
import com.postsaimanager.core.domain.skills.SkillParser
import org.junit.jupiter.api.Test
import java.io.File

/** The skills shipped in `app/src/main/assets/skills`: each must parse, and what it tells the model must be something the app can run. */
class BundledSkillsTest {

    private val root = File("src/main/assets/skills")

    private val folders: List<File> = root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }

    private fun parse(folder: File): Skill {
        val result = SkillParser.parse(File(folder, "SKILL.md").readText())
        check(result is SkillParseResult.Parsed) { "${folder.name}: ${(result as SkillParseResult.Invalid).errors}" }
        return result.skill
    }

    @Test
    fun `the six skills are there, each a folder with a SKILL md`() {
        assertThat(folders.map { it.name }).containsExactly(
            "add-deadline-to-calendar",
            "create-calendar-event",
            "draft-reply-to-letter",
            "remind-me-before-deadline",
            "schedule-reminder",
            "send-email",
        )
        folders.forEach { assertThat(File(it, "SKILL.md").isFile).isTrue() }
    }

    @Test
    fun `every skill parses and is named like its folder`() {
        folders.forEach { folder ->
            val skill = parse(folder)
            assertThat(skill.name).isEqualTo(folder.name)
            assertThat(skill.description).isNotEmpty()
            assertThat(skill.instructions).isNotEmpty()
        }
    }

    @Test
    fun `every intent a skill names is one the app runs`() {
        val intentLine = Regex("(?m)^\\s*-?\\s*intent:\\s*(\\S+)")
        val wires = AgentIntent.entries.map { it.wire }
        folders.map { parse(it) }.forEach { skill ->
            val named = intentLine.findAll(skill.instructions).map { it.groupValues[1] }.toList()
            assertThat(named).isNotEmpty()
            assertThat(wires).containsAtLeastElementsIn(named)
        }
    }

    @Test
    fun `every parameter a skill lists is one the parser reads`() {
        // Send each skill's documented parameters back through the parser: a name the parser does not read would be refused.
        val sample = mapOf(
            "send_email" to """{"extra_email":"a@b.de","extra_subject":"s","extra_text":"t"}""",
            "create_calendar_event" to """{"title":"t","description":"d","begin_time":"2026-11-05T09:00:00","end_time":"2026-11-05T10:00:00"}""",
            "schedule_notification" to """{"message":"m","year":2026,"month":11,"day":2,"hour":9,"minute":0,"document_id":"d"}""",
        )
        folders.map { parse(it) }.forEach { skill ->
            val intent = Regex("intent:\\s*(\\w+)").find(skill.instructions)!!.groupValues[1]
            sample[intent]?.let { assertThat(AgentActionParser.parse(intent, it)).isInstanceOf(ActionParse.Parsed::class.java) }
            val params = Regex("(?m)^\\s+- (\\w+(?:, \\w+)*):").findAll(skill.instructions).flatMap { it.groupValues[1].split(", ") }.toList()
            val known = setOf(
                "extra_email", "extra_subject", "extra_text", "title", "description", "begin_time", "end_time",
                "message", "year", "month", "day", "hour", "minute", "document_id",
            )
            assertThat(known).containsAtLeastElementsIn(params.filter { it != "intent" && it != "parameters" }.toSet())
        }
    }

    @Test
    fun `no skill fetches, scripts or sends anything by itself`() {
        folders.map { parse(it) }.forEach { skill ->
            val text = skill.content().lowercase()
            assertThat(text).doesNotContain("http")
            assertThat(text).doesNotContain("run_js")
            assertThat(text).doesNotContain("run_mcp")
            assertThat(text).doesNotContain("send_sms")
            assertThat(text).doesNotContain("read_calendar_events")
        }
    }

    @Test
    fun `the letter skills tell the model to ask rather than invent, and to write in the letter's language`() {
        val letterSkills = listOf("draft-reply-to-letter", "add-deadline-to-calendar", "remind-me-before-deadline").map { name -> parse(File(root, name)) }

        letterSkills.forEach { assertThat(it.instructions).contains("Never invent") }
        assertThat(letterSkills[0].instructions).contains("same language as the letter")
        assertThat(letterSkills[0].instructions).contains("reference")
    }
}
