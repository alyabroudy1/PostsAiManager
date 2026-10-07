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

// Modified by PostsAiManager: adapted from google-ai-edge/gallery SkillManager.convertSkillMdToProto. Pure Kotlin (no proto, no
// Context); the JS-skill metadata (require-secret, homepage) is dropped; BOM and CRLF are tolerated; quoted values are unquoted.

package com.postsaimanager.core.domain.skills

/** The result of reading one `SKILL.md`. */
sealed interface SkillParseResult {
    data class Parsed(val skill: Skill) : SkillParseResult

    /** Why the file is not a skill; the messages are for a log or a developer, not for the user. */
    data class Invalid(val errors: List<String>) : SkillParseResult
}

/**
 * Reads a `SKILL.md`: a frontmatter between two `---` lines with `name:` and `description:` (one line each), then the markdown
 * instructions.
 *
 * The rules are the Gallery's: the text is split at `---`; the part after the first `---` is the header, everything from the third
 * part on (rejoined with `---`, so a rule or table inside the body survives) is the body. A `metadata:` line in the header ends
 * the `name`/`description` lines; what follows it is not read (the Gallery uses it for JS-skill secrets, which this app has not).
 */
object SkillParser {

    private const val DELIMITER = "---"
    private const val METADATA = "metadata:"

    fun parse(markdown: String): SkillParseResult {
        val text = markdown.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val parts = text.split(DELIMITER)
        if (parts.size < 3) return SkillParseResult.Invalid(listOf("Invalid format: Expected at least two '---' sections."))

        var name: String? = null
        var description: String? = null
        var timeAware = false
        for (line in parts[1].trim().lines()) {
            val trimmed = line.trim()
            if (trimmed == METADATA) break
            when {
                trimmed.startsWith("time-aware:") -> timeAware = unquote(trimmed.substringAfter("time-aware:")).equals("true", ignoreCase = true)
                trimmed.startsWith("name:") -> name = unquote(trimmed.substringAfter("name:"))
                trimmed.startsWith("description:") -> description = unquote(trimmed.substringAfter("description:"))
            }
        }

        val errors = buildList {
            if (name.isNullOrEmpty()) add("Missing or empty 'name' in the header.")
            if (description.isNullOrEmpty()) add("Missing or empty 'description' in the header.")
        }
        if (errors.isNotEmpty()) return SkillParseResult.Invalid(errors)

        val instructions = parts.drop(2).joinToString(DELIMITER).trim()
        return SkillParseResult.Parsed(Skill(name = name!!, description = description!!, instructions = instructions, timeAware = timeAware))
    }

    /** The value without surrounding whitespace and one pair of matching quotes. */
    private fun unquote(raw: String): String {
        val v = raw.trim()
        if (v.length >= 2 && (v.first() == '"' && v.last() == '"' || v.first() == '\'' && v.last() == '\'')) return v.substring(1, v.length - 1).trim()
        return v
    }
}
