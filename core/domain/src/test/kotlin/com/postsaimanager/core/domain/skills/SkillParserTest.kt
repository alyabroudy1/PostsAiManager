package com.postsaimanager.core.domain.skills

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SkillParserTest {

    private fun parsed(markdown: String): Skill = (SkillParser.parse(markdown) as SkillParseResult.Parsed).skill

    private fun errors(markdown: String): List<String> = (SkillParser.parse(markdown) as SkillParseResult.Invalid).errors

    @Test
    fun `reads name, description and the body`() {
        val skill = parsed("---\nname: send-email\ndescription: Send an email.\n---\n\n# Send email\n\nCall the tool.\n")

        assertThat(skill.name).isEqualTo("send-email")
        assertThat(skill.description).isEqualTo("Send an email.")
        assertThat(skill.instructions).isEqualTo("# Send email\n\nCall the tool.")
    }

    @Test
    fun `time-aware is read from the frontmatter and is off by default`() {
        assertThat(parsed("---\nname: a\ndescription: b\ntime-aware: true\n---\nbody").timeAware).isTrue()
        assertThat(parsed("---\nname: a\ndescription: b\ntime-aware: false\n---\nbody").timeAware).isFalse()
        assertThat(parsed("---\nname: a\ndescription: b\n---\nbody").timeAware).isFalse()
    }

    @Test
    fun `a rule or table inside the body survives, because only the first two delimiters are the frontmatter`() {
        val skill = parsed("---\nname: a\ndescription: b\n---\nfirst\n---\nsecond\n")

        assertThat(skill.instructions).isEqualTo("first\n---\nsecond")
    }

    @Test
    fun `fewer than two delimiters is not a skill`() {
        assertThat(errors("name: a\ndescription: b")).containsExactly("Invalid format: Expected at least two '---' sections.")
        assertThat(errors("---\nname: a\ndescription: b\n")).hasSize(1)
        assertThat(errors("")).hasSize(1)
    }

    @Test
    fun `a missing or empty name or description is reported, both at once`() {
        assertThat(errors("---\ndescription: b\n---\nbody")).containsExactly("Missing or empty 'name' in the header.")
        assertThat(errors("---\nname: a\ndescription:\n---\nbody")).containsExactly("Missing or empty 'description' in the header.")
        assertThat(errors("---\n---\nbody")).hasSize(2)
    }

    @Test
    fun `an empty body is still a skill`() {
        assertThat(parsed("---\nname: a\ndescription: b\n---\n").instructions).isEmpty()
    }

    @Test
    fun `windows line endings and a byte order mark are tolerated`() {
        val skill = parsed("﻿---\r\nname: a\r\ndescription: b\r\n---\r\nline one\r\nline two\r\n")

        assertThat(skill.name).isEqualTo("a")
        assertThat(skill.description).isEqualTo("b")
        assertThat(skill.instructions).isEqualTo("line one\nline two")
    }

    @Test
    fun `one pair of quotes around a value is removed, and a colon inside the value stays`() {
        val skill = parsed("---\nname: \"quoted\"\ndescription: 'Use it: when asked.'\n---\nbody")

        assertThat(skill.name).isEqualTo("quoted")
        assertThat(skill.description).isEqualTo("Use it: when asked.")
    }

    @Test
    fun `metadata ends the name and description lines and is not read`() {
        val skill = parsed(
            "---\nname: real\ndescription: the description\nmetadata:\n  name: ignored\n  description: ignored\n  require-secret: true\n---\nbody",
        )

        assertThat(skill.name).isEqualTo("real")
        assertThat(skill.description).isEqualTo("the description")
    }

    @Test
    fun `name and description after the metadata block do not count`() {
        assertThat(errors("---\nmetadata:\n  x: y\nname: late\ndescription: late\n---\nbody")).hasSize(2)
    }

    @Test
    fun `an indented name inside the header is still read, like the Gallery does`() {
        assertThat(parsed("---\n  name: indented\n  description: ok\n---\nbody").name).isEqualTo("indented")
    }

    @Test
    fun `content is the frontmatter echoed and the body, as load_skill hands it to the model`() {
        val skill = Skill("a", "b", "body")

        assertThat(skill.content()).isEqualTo("---\nname: a\ndescription: b\n---\n\nbody")
    }

    @Test
    fun `names and descriptions for the prompt are one line per skill`() {
        val list = SkillPrompt.namesAndDescriptions(listOf(Skill("one", "first", ""), Skill("two", "second", "")))

        assertThat(list).isEqualTo("- Skill name: \"one\"\n- Description: first\n\n- Skill name: \"two\"\n- Description: second")
    }

    @Test
    fun `a skill name written by a model matches in case, hyphens, underscores and spaces`() {
        assertThat(SkillNames.matches("send-email", "send_email")).isTrue()
        assertThat(SkillNames.matches("send-email", " Send Email ")).isTrue()
        assertThat(SkillNames.matches("send-email", "SEND-EMAIL")).isTrue()
        assertThat(SkillNames.matches("send-email", "send-emails")).isFalse()
    }

    @Test
    fun `the catalog loads by the forgiving name and lists the prompt lines`() = runTest {
        val catalog = object : SkillCatalog {
            override suspend fun skills() = listOf(Skill("draft-reply-to-letter", "Reply.", "x"), Skill("send-email", "Send.", "y"))
        }

        assertThat(catalog.load("draft_reply_to_letter")?.instructions).isEqualTo("x")
        assertThat(catalog.load("nope")).isNull()
        assertThat(catalog.namesAndDescriptions()).isEqualTo(
            "- Skill name: \"draft-reply-to-letter\"\n- Description: Reply.\n\n- Skill name: \"send-email\"\n- Description: Send.",
        )
    }
}
