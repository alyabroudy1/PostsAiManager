/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Modified by PostsAiManager: adapted from google-ai-edge/gallery (skills/SkillExtensions.kt, SkillManager.matchesSkillName and
// getSelectedSkillsNamesAndDescriptions): the Skill proto becomes a plain data class, the skill source a port.

package com.postsaimanager.core.domain.skills

/**
 * An Agent Skill: DATA, not code. A folder with a `SKILL.md` whose frontmatter names the skill and says when to use it, and whose
 * body tells the model what to do (see `documentation/agent-skills.md`). The model sees only [name] and [description] up front;
 * [instructions] enter its context when it loads the skill.
 */
data class Skill(
    val name: String,
    val description: String,
    /** The markdown body of `SKILL.md`, after the frontmatter. */
    val instructions: String,
    /**
     * The folder the skill lives in (`skills/<folder>/`), which `run_js` and a skill's webview address its files by. Empty for a
     * skill that has no folder of its own (a test double): it then has no scripts.
     */
    val folder: String = "",
    /** The files of its `scripts/` folder, a JS skill's entry points (`index.html`); empty for a skill that is only instructions. */
    val scripts: List<String> = emptyList(),
    /**
     * Declared by the skill itself (`time-aware: true` in its frontmatter): `load_skill` appends the phone's current date and time
     * to the text it returns, so the model needs no separate clock call. Data in the skill, not a list in code.
     */
    val timeAware: Boolean = false,
) {
    /** True for a skill the model runs with `run_js`: it ships at least one script. */
    val isJsSkill: Boolean get() = scripts.isNotEmpty()

    /**
     * What a `load_skill` call hands back to the model: the skill as the Gallery formats it (frontmatter echoed, then the body).
     * Adapted from the Gallery's `SKILL_INSTRUCTIONS_TEMPLATE` (Apache 2.0).
     */
    fun content(): String = "---\nname: $name\ndescription: $description\n---\n\n$instructions"
}

/**
 * The port to the skills the app ships. Skills are bundled or (later) imported from a local folder, never fetched: the catalog has
 * no URL, no remote list.
 */
interface SkillCatalog {

    /** Every skill that parsed; one that failed to parse is left out (and logged by the implementation). */
    suspend fun skills(): List<Skill>

    /** The skill [name] stands for ([SkillNames.matches]: case, hyphens, underscores and spaces are forgiven), or null. */
    suspend fun load(name: String): Skill? = skills().firstOrNull { SkillNames.matches(it.name, name) }

    /** The names and descriptions for the model's system prompt, one `- name: description` line per skill. */
    suspend fun namesAndDescriptions(): String = SkillPrompt.namesAndDescriptions(skills())
}

/** The one place a skill name typed by a model is compared with a skill's own name. */
object SkillNames {
    private val SEPARATORS = Regex("[\\s_]+")

    /**
     * True when [target] names the skill called [actual]. Adapted from the Gallery's `SkillManager.matchesSkillName` (Apache 2.0): a
     * small model writes `send_email`, `Send Email` or `send-email` for the same skill.
     */
    fun matches(actual: String, target: String): Boolean {
        val a = actual.trim().lowercase().replace(SEPARATORS, "-")
        val t = target.trim().lowercase().replace(SEPARATORS, "-")
        return a == t || actual.equals(target.trim(), ignoreCase = true)
    }
}

/** What the system prompt says about the skills. */
object SkillPrompt {

    /**
     * The skills as a compact list, one `- name: description` line each (shorter than the Gallery's `formatSelectedSkills` two lines per
     * skill: every character of the system prompt is prefilled at each chat warm-up).
     */
    fun namesAndDescriptions(skills: List<Skill>): String =
        skills.joinToString("\n") { "- ${it.name}: ${it.description}" }
}
