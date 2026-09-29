package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** The scoring interpreter through the real pipeline, with a fake session whose "model" scores by a rule the test sets. */
class ZoneScoringInterpreterTest {

    private val letter = Letters.invoice

    /** Says Yes (+5) to the pairs of value and statement in [yes], No (-5) to everything else. */
    private fun run(profile: ScoringProfile = ScoringProfile(), yes: (String) -> Boolean): Pair<com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result, FakePromptSession> {
        val session = FakePromptSession().apply {
            scorer = { c -> if (yes(c)) 5.0 else -5.0 }
            responder = { _, _ -> "\"text\"" }
        }
        val interpreter = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096, profile = profile)
        return runBlocking { ExtractionV2Pipeline().run(letter.pages, interpreter, 4096) } to session
    }

    private fun says(value: String, statement: String): (String) -> Boolean = { c -> c.contains("«$value»") && c.contains(statement) }

    @Test
    fun `the candidate the model says yes to fills the slot and nothing else does`() {
        val (result, session) = run { c ->
            says("28.09.2026", "the date of the letter itself")(c) ||
                says("1.284,50 €", "the main amount")(c) ||
                says("Musterfirma GmbH", "the sender")(c) ||
                says("Erika Mustermann", "the addressee")(c) ||
                c.contains("Is this document an invoice or bill")
        }
        assertThat(result.documentType?.id).isEqualTo("bill")
        assertThat(result.slots.entries.first { it.key.json == "letter_date" }.value.normalized).isEqualTo("2026-09-28")
        assertThat(result.slots.entries.first { it.key.json == "total" }.value.normalized).isEqualTo("1284.50 EUR")
        assertThat(result.parties.sender?.name).isEqualTo("Musterfirma GmbH")
        assertThat(result.parties.all.first { it.role == PartyRole.ADDRESSEE }.name).isEqualTo("Erika Mustermann")
        // Nothing was said yes to for the IBAN or the deadline: the answer is none, not the first candidate.
        assertThat(result.slots.keys.map { it.json }).doesNotContain("iban")
        assertThat(result.slots.keys.map { it.json }).doesNotContain("due_date")
        assertThat(session.opens.size).isEqualTo(2)
    }

    @Test
    fun `no option label or candidate id is ever shown`() {
        val (_, session) = run { false }
        val idPattern = Regex("\\b[A-Z]{1,2}\\d{1,3}:")
        assertThat(session.scored).isNotEmpty()
        for (c in session.scored.flatten()) assertThat(idPattern.containsMatchIn(c)).isFalse()
        // Neither the prefixes nor the questions carry a candidate table; a question carries its zone and the zone's hint.
        for (prefix in session.opens) assertThat(prefix).doesNotContain("CANDIDATES")
        for (c in session.scored.flatten()) assertThat(c).doesNotContain("CANDIDATES")
        assertThat(session.scored.flatten().any { it.contains("ZONE address-field. HINT:") }).isTrue()
    }

    @Test
    fun `the neighbour glimpse is context only and adds no candidate`() {
        val plain = run { false }.second.scored.flatten()
        val ctx = FakePromptSession().apply { scorer = { -5.0 }; responder = { _, _ -> "\"text\"" } }.also { s ->
            runBlocking {
                ExtractionV2Pipeline().run(letter.pages, ZoneScoringInterpreter(FakeAiEngine(), s, contextTokens = 4096, neighbourContext = true), 4096)
            }
        }.scored.flatten()
        assertThat(plain.none { it.contains("CONTEXT ONLY") }).isTrue()
        val withGlimpse = ctx.filter { it.contains("ZONE address-field.") }
        assertThat(withGlimpse).isNotEmpty()
        assertThat(withGlimpse.all { it.contains("CONTEXT ONLY, the zone just above (") }).isTrue()
        // The same candidates are scored either way (the glimpse selects nothing).
        assertThat(ctx.size).isEqualTo(plain.size)
    }

    @Test
    fun `the abstain threshold decides between a value and none`() {
        val yesLetterDate = says("28.09.2026", "the date of the letter itself")
        val strict = ScoringProfile(thresholds = mapOf("slot:letter_date" to 8.0))
        val (result, _) = run(strict, yesLetterDate)
        assertThat(result.slots.keys.map { it.json }).doesNotContain("letter_date")
        val (loose, _) = run(ScoringProfile(thresholds = mapOf("slot:letter_date" to 2.0)), yesLetterDate)
        assertThat(loose.slots.keys.map { it.json }).contains("letter_date")
    }

    @Test
    fun `the sender is never scored as the addressee`() {
        // A model that says yes to every name for every role still cannot make the sender the addressee.
        val (result, _) = run { c -> c.contains("the sender") || c.contains("the addressee") }
        val sender = result.parties.sender
        val addressee = result.parties.all.firstOrNull { it.role == PartyRole.ADDRESSEE }
        if (sender != null && addressee != null) assertThat(addressee.name).isNotEqualTo(sender.name)
    }

    @Test
    fun `the schema's core slots all have a statement of their own`() {
        for (slot in Slots.CORE) assertThat(ScoringDescriptions.ofSlot(slot)).doesNotContain("the ${slot.label.lowercase()}")
        assertThat(ExtractionSchema.DEFAULT.types.all { it.description.isNotBlank() }).isTrue()
    }
}
