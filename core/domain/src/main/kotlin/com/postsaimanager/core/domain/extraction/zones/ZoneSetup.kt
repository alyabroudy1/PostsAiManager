package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.InterpretationRequest
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates

/**
 * What both zone interpreters share for one letter: the matched template, the zoned view of the letter,
 * the plan of which question goes where, and how a prompt is cut into a prefix (decoded once) and a
 * tail (appended to every question).
 */
internal class ZoneSetup(
    private val engine: AiEngine,
    layout: LetterLayout,
    offered: OfferedCandidates,
    matcher: TemplateMatcher,
    pageAspect: Float?,
) {
    val match: TemplateMatch = matcher.match(layout, pageAspect)
    val template: LayoutTemplate = match.template
    val zoned = ZonedLetter(layout, template, offered)
    val plan = ZonePlan(template, zoned)

    /** The zones whose text a body session holds as its prefix (the ones that are not asked one at a time). */
    val bodyZones: List<LetterZone> =
        listOf(LetterZone.SUBJECT, LetterZone.BODY, LetterZone.PAYMENT_SECTION).map { zoned.mapped(it) }.distinct()

    /**
     * Renders [user] as the model's chat template wants it and cuts the result where a question goes:
     * everything before is the prefix, everything after closes the user turn and opens the assistant's.
     */
    fun frame(system: String, user: String): Pair<String, String> {
        val rendered = engine.formatPrompt(
            listOf(AiChatMessage(AiChatRole.SYSTEM, system), AiChatMessage(AiChatRole.USER, user + MARK)),
        )
        val at = rendered.lastIndexOf(MARK)
        return if (at < 0) rendered to "" else rendered.substring(0, at) to rendered.substring(at + MARK.length)
    }

    companion object {
        private const val MARK = "@@QUESTION@@"

        /** Characters of body text that fit a window of [contextTokens], leaving room for the instructions and a question. */
        fun bodyBudgetChars(contextTokens: Int): Int = ((contextTokens - BODY_RESERVE_TOKENS) * CHARS_PER_TOKEN).toInt().coerceAtLeast(MIN_BODY_CHARS)

        private const val BODY_RESERVE_TOKENS = 1100
        private const val CHARS_PER_TOKEN = 2.5
        private const val MIN_BODY_CHARS = 800
    }
}

/** The request's layout, or null when the interpreter was not given one (it then cannot read by zones). */
internal fun InterpretationRequest.zoneLayout(): LetterLayout? = layout
