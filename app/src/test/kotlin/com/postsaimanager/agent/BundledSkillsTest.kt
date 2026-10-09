package com.postsaimanager.agent

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.skills.AgentActionParser
import com.postsaimanager.core.domain.skills.AgentIntent
import com.postsaimanager.core.domain.skills.ChatToolsPrompt
import com.postsaimanager.core.domain.skills.ActionParse
import com.postsaimanager.core.domain.skills.Skill
import com.postsaimanager.core.domain.skills.SkillParseResult
import com.postsaimanager.core.domain.skills.SkillPrompt
import com.postsaimanager.core.domain.skills.SkillParser
import org.junit.jupiter.api.Test
import java.io.File

/** The skills shipped in `app/src/main/assets/skills`: each must parse, and what it tells the model must be something the app can run. */
class BundledSkillsTest {

    private val root = File("src/main/assets/skills")

    private val allFolders: List<File> = root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }

    /** A JS skill has a `scripts/` folder and is run with `run_js`; the others only call `run_intent`. */
    private val jsFolders: List<File> = allFolders.filter { File(it, "scripts").isDirectory }

    /** The intent skills: what the tests about intents and parameters read. */
    private val folders: List<File> = allFolders - jsFolders.toSet()

    private fun parse(folder: File): Skill {
        val result = SkillParser.parse(File(folder, "SKILL.md").readText())
        check(result is SkillParseResult.Parsed) { "${folder.name}: ${(result as SkillParseResult.Invalid).errors}" }
        return result.skill
    }

    @Test
    fun `the four intent skills and the one JS test skill are there, each a folder with a SKILL md, and no two intent skills overlap`() {
        // One skill per action: a small model choosing between near-duplicates picked the wrong one.
        assertThat(folders.map { it.name }).containsExactly(
            "create-calendar-event",
            "draft-reply-to-letter",
            "schedule-reminder",
            "send-email",
        )
        assertThat(jsFolders.map { it.name }).containsExactly("calculate-hash")
        allFolders.forEach { assertThat(File(it, "SKILL.md").isFile).isTrue() }
    }

    @Test
    fun `the JS skill is the Gallery's, runs with run_js, and its scripts are bundled files that fetch nothing`() {
        val skill = parse(File(root, "calculate-hash"))
        assertThat(skill.instructions).contains("run_js")
        assertThat(skill.instructions).contains("index.html")

        val scripts = File(root, "calculate-hash/scripts")
        assertThat(scripts.list().orEmpty().toList()).containsExactly("index.html", "index.js")
        scripts.listFiles().orEmpty().forEach { file ->
            val text = file.readText().lowercase()
            // The Apache licence URL in the header is the only address, and a script never loads from one.
            val withoutLicenceUrl = text.replace("http://www.apache.org/licenses/license-2.0", "")
            assertThat(withoutLicenceUrl).doesNotContain("http://")
            assertThat(withoutLicenceUrl).doesNotContain("https://")
            assertThat(text).doesNotContain("fetch(")
            assertThat(text).doesNotContain("xmlhttprequest")
            assertThat(text).doesNotContain("websocket")
        }
        // It copies the Gallery's licence header.
        assertThat(File(scripts, "index.js").readText()).contains("Copyright 2026 Google LLC")
    }

    @Test
    fun `every skill parses and is named like its folder`() {
        allFolders.forEach { folder ->
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
            "schedule_notification" to """{"message":"m","year":2026,"month":11,"day":2,"hour":9,"minute":0}""",
        )
        folders.map { parse(it) }.forEach { skill ->
            val intent = Regex("intent:\\s*(\\w+)").find(skill.instructions)!!.groupValues[1]
            sample[intent]?.let { assertThat(AgentActionParser.parse(intent, it)).isInstanceOf(ActionParse.Parsed::class.java) }
            val params = Regex("(?m)^\\s+- (\\w+(?:, \\w+)*):").findAll(skill.instructions).flatMap { it.groupValues[1].split(", ") }.toList()
            val known = setOf(
                "extra_email", "extra_subject", "extra_text", "title", "description", "begin_time", "end_time",
                "message", "year", "month", "day", "hour", "minute", "in_minutes", "in_hours", "in_days",
            )
            // The app always uses the chat's document; a model-filled id would only be a wrong letter reference.
            assertThat(skill.instructions).doesNotContain("document_id")
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
    fun `only the date skills declare they are time-aware, and they point to the time they are given`() {
        // The time comes with the skill text (load_skill appends it), so no separate clock call is needed.
        val aware = allFolders.filter { parse(it).timeAware }.map { it.name }
        assertThat(aware).containsExactly("schedule-reminder", "create-calendar-event")
        aware.map { parse(File(root, it)) }.forEach { skill ->
            assertThat(skill.instructions).contains("\"Now:\"")
            assertThat(skill.instructions).contains("Never invent a date")
        }
        // The reminder covers both ways of naming a time, and asks for a self-contained line that names the sender or subject, not pronouns.
        val reminder = parse(File(root, "schedule-reminder")).instructions
        assertThat(reminder).contains("three days before")
        assertThat(reminder).contains("one self-contained line")
        assertThat(reminder).contains("Start with the sender's or organisation's name")
        assertThat(reminder).contains("Pay Stadtwerke Musterstadt")
        assertThat(reminder).contains("Never use pronouns")
        assertThat(parse(File(root, "create-calendar-event")).instructions).contains("without pronouns")
    }

    @Test
    fun `every action skill's description says it applies only on an explicit request, and the model's skill list shows it`() {
        // A plain question ("When is the payment due?") once made the model load the reminder skill: the listed description decides.
        val skills = folders.map { parse(it) }
        skills.forEach { skill ->
            assertThat(skill.description).startsWith("Only when the user explicitly asks")
        }
        val listing = SkillPrompt.namesAndDescriptions(skills)
        skills.forEach { assertThat(listing).contains("- ${it.name}: ${it.description}") }
        assertThat(listing).contains("explicitly asks to be reminded or to set a reminder or notification")
    }

    @Test
    fun `every description is at most 100 characters and the whole tools prompt fits its budget`() {
        val skills = allFolders.map { parse(it) }
        skills.forEach { assertThat(it.description.length).isAtMost(100) }

        val prompt = ChatToolsPrompt.build(SkillPrompt.namesAndDescriptions(skills))

        assertThat(prompt.length).isAtMost(ChatToolsPrompt.MAX_CHARS)
    }

    @Test
    fun `every intent skill is short, because its whole text is read by the model after load_skill`() {
        folders.forEach { assertThat(parse(it).instructions.length).isLessThan(1200) }
    }

    @Test
    fun `the reminder skill names the relative offset for a time from now and the date fields for an absolute one`() {
        val reminder = parse(File(root, "schedule-reminder")).instructions

        listOf("in_minutes", "in_hours", "in_days").forEach { assertThat(reminder).contains("- $it:") }
        assertThat(reminder).contains("needs no clock call")
        assertThat(reminder).contains("\"tomorrow at 9\"")
        assertThat(reminder).doesNotContain("MUST first call")
        // A documented offset is one the parser turns into a time (the clock is the app's).
        val parsed = AgentActionParser.parse("schedule_notification", """{"message":"m","in_minutes":2}""") as ActionParse.Parsed
        assertThat((parsed.action as com.postsaimanager.core.domain.skills.AgentAction.ScheduleReminder).at).isNotNull()
    }

    @Test
    fun `the letter skills tell the model to ask rather than invent, and to write in the letter's language`() {
        val letterSkills = listOf("draft-reply-to-letter", "create-calendar-event", "schedule-reminder").map { name -> parse(File(root, name)) }

        letterSkills.forEach { assertThat(it.instructions).contains("Never invent") }
        assertThat(letterSkills[0].instructions).contains("same language as the letter")
        assertThat(letterSkills[0].instructions).contains("reference")
    }
}
