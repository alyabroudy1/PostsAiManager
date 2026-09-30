package com.postsaimanager.core.domain.extraction.address

import com.postsaimanager.core.domain.extraction.candidates.Validation
import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.Party
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.PartyRelation
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.SlotOrigin
import com.postsaimanager.core.domain.extraction.v2.SlotValue
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking

/** One address line at vertical position [y] (page fraction), [left]..[right] wide. */
fun line(text: String, y: Float, left: Float = 0.10f, right: Float = 0.40f): AddressLine =
    AddressLine(text, 1, TextBounds(left, y, right, y + 0.012f))

/** A page-1 layout line in [zone] at [y]. */
fun layoutLine(text: String, y: Float, zone: LetterZone, left: Float = 0.10f, right: Float = 0.40f): LayoutLine =
    LayoutLine(text, 1, TextBounds(left, y, right, y + 0.012f), 0.9f, zone)

/** A party as the model gave it, with its own confidence word 0.9. */
fun party(name: String, role: PartyRole = PartyRole.ADDRESSEE, kind: PartyKind = PartyKind.PERSON, relation: PartyRelation = PartyRelation.NONE) =
    Party(
        role, kind, relation,
        SlotValue(null, null, name, name, 1, null, name, SlotOrigin.MODEL_CHOICE, 0.9f, 0.9f, Validation.Unchecked),
    )

/** An opened fake session whose scores say Yes (+5) to the (line, statement) pairs in [rules] and No (-5) to everything else. */
fun sessionSaying(vararg rules: Pair<String, LineAsk>): FakePromptSession = FakePromptSession().apply {
    scorer = { c -> if (rules.any { (text, ask) -> c.contains("Is «$text»") && c.contains(" ${ask.statement}? Answer:") }) 5.0 else -5.0 }
    runBlocking { open("the letter") }
}

/** How many scores a session was asked for. */
val FakePromptSession.cells: Int get() = scored.sumOf { it.size }
