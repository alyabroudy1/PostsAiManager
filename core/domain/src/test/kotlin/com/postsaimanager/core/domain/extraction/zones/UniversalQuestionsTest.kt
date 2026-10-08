package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.Letter
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.domain.extraction.v2.ValueMeaning
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Phase 2 of the type-free extraction: one universal question set for every document, each with a calibrated "none", and the meaning of the
 * dates and amounts kept. A fake session whose "model" scores by a rule the test sets.
 */
class UniversalQuestionsTest {

    private val partyRoles = listOf(
        QuestionNames.SENDER, QuestionNames.ADDRESSEE, QuestionNames.CARE_OF, QuestionNames.CONTACT, QuestionNames.SUBJECT_PERSON,
    )

    private fun run(letter: Letter, profile: ScoringProfile = ScoringProfile(), score: (String) -> Double): Pair<ExtractionV2Result, FakePromptSession> {
        val session = FakePromptSession().apply {
            scorer = score
            responder = { _, _ -> "\"text\"" }
        }
        val interpreter = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096, profile = profile)
        return runBlocking { ExtractionV2Pipeline().run(letter.pages, interpreter, 4096) } to session
    }

    private fun says(value: String, statement: String): (String) -> Boolean = { c -> c.contains("«$value»") && c.contains(statement) }

    private val nameMargins = ScoringProfile(baselineMargins = partyRoles.associateWith { 0.0 })

    /** The model leans a little Yes on any name as a party (+2, above the threshold) and more on a made-up name (+5): nothing in the page beats it. */
    private val leansYesOnEveryName: (String) -> Double = { c ->
        when {
            c.contains("«${ScoringDescriptions.PARTY_BASELINE_NAME}»") -> 5.0
            partyRoles.any { c.contains(ScoringDescriptions.ofRole(it)) } -> 2.0
            else -> -5.0
        }
    }

    @Test
    fun `a screenshot-like document with no address window gets no sender and no addressee when nothing beats the baseline`() {
        val (result, _) = run(Letters.receipt, nameMargins, leansYesOnEveryName)
        assertThat(result.parties.sender).isNull()
        assertThat(result.parties.all).isEmpty()
        // The same scores without the baseline margin would have made the best of a bad lot the sender: it is the baseline that says none.
        val (without, _) = run(Letters.receipt, ScoringProfile(), leansYesOnEveryName)
        assertThat(without.parties.sender).isNotNull()
    }

    @Test
    fun `a letter still gets its parties when the names beat the baseline`() {
        val (result, _) = run(Letters.invoice, nameMargins) { c ->
            when {
                c.contains("«${ScoringDescriptions.PARTY_BASELINE_NAME}»") -> -5.0
                says("Musterfirma GmbH", ScoringDescriptions.ofRole(QuestionNames.SENDER))(c) -> 5.0
                says("Erika Mustermann", ScoringDescriptions.ofRole(QuestionNames.ADDRESSEE))(c) -> 5.0
                else -> -5.0
            }
        }
        assertThat(result.parties.sender?.name).isEqualTo("Musterfirma GmbH")
        assertThat(result.parties.all.first { it.role == PartyRole.ADDRESSEE }.name).isEqualTo("Erika Mustermann")
    }

    @Test
    fun `the contact person is asked on every category, the general Document and the short texts included`() {
        val contact = ScoringDescriptions.ofRole(QuestionNames.CONTACT)
        val schema = ExtractionSchema.DEFAULT
        val families = schema.categoryFamilies(DocDirection.INCOMING) + schema.abstain!!
        assertThat(families.map { it.id }).containsAtLeast("appointment_reminder", "message_note", "notice_decision", "free_form")
        for (family in families) {
            val (result, session) = run(Letters.receipt) { c -> if (!family.scored || !c.contains("Is this document ${family.description}? Answer:")) -5.0 else 5.0 }
            assertThat(result.documentType?.id).isEqualTo(family.id)
            assertThat(session.scored.flatten().any { it.contains(contact) }).isTrue()
        }
    }

    @Test
    fun `every party and every reference question has a content-free baseline for every document`() {
        val margins = ModelProfiles.QWEN35_08B.scoring.baselineMargins
        assertThat(margins.keys).containsAtLeastElementsIn(partyRoles)
        val references = ExtractionSchema.DEFAULT.allSlots.filter { it.kind.name.startsWith("REFERENCE") }.map { QuestionNames.slot(it.json) }
        assertThat(references).isNotEmpty()
        assertThat(margins.keys).containsAtLeastElementsIn(references)
        // The baselines are scored for any document: here a general Document under the shipped profile.
        val (_, session) = run(Letters.receipt, ModelProfiles.QWEN35_08B.scoring) { -5.0 }
        val probes = session.scored.flatten()
        assertThat(probes.any { it.contains("«${ScoringDescriptions.PARTY_BASELINE_NAME}»") }).isTrue()
        assertThat(probes.any { it.contains("«${ScoringDescriptions.REFERENCE_BASELINE_VALUE}»") }).isTrue()
    }

    @Test
    fun `a reference must beat the made-up reference by its margin, else the field stays empty`() {
        val statement = ScoringDescriptions.ofSlot(Slots.INVOICE_NO)
        fun score(baseline: Double): (String) -> Double = { c ->
            when {
                c.contains("«${ScoringDescriptions.REFERENCE_BASELINE_VALUE}»") -> baseline
                c.contains("«RE-2026-0815»") && c.contains("$statement? Answer:") -> 2.0
                else -> -5.0
            }
        }
        val margin = ScoringProfile(baselineMargins = mapOf(QuestionNames.slot("invoice_no") to 0.0))
        assertThat(run(Letters.invoice, margin, score(baseline = -5.0)).first.slots.keys.map { it.json }).contains("invoice_no")
        assertThat(run(Letters.invoice, margin, score(baseline = 5.0)).first.slots.keys.map { it.json }).doesNotContain("invoice_no")
        // No margin set for the question: taken as before.
        assertThat(run(Letters.invoice, ScoringProfile(), score(baseline = 5.0)).first.slots.keys.map { it.json }).contains("invoice_no")
    }

    private val dueStatement = "the date by which the reader must pay or act"
    private val appointment = ValueMeanings.DEFAULT.byId("APPOINTMENT")!!
    private val totalDue = ValueMeanings.DEFAULT.byId("TOTAL_DUE")!!

    private val dueMeaning = ValueMeanings.DEFAULT.byId("DUE_DATE")!!

    private fun meaningScores(baseline: Double = -5.0, dateMeaning: ValueMeaning = dueMeaning): (String) -> Double = { c ->
        when {
            c.contains("«${ScoringDescriptions.DATE_BASELINE_VALUE}»") || c.contains("«${ScoringDescriptions.AMOUNT_BASELINE_VALUE}»") -> baseline
            says("15.10.2026", dueStatement)(c) -> 5.0
            says("1.284,50 €", "the main amount")(c) -> 5.0
            says("15.10.2026", dateMeaning.description)(c) -> 5.0
            says("1.284,50 €", totalDue.description)(c) -> 5.0
            else -> -5.0
        }
    }

    @Test
    fun `a date's meaning is attached to the existing due-date slot, which keeps its value`() {
        val (result, _) = run(Letters.invoice, ScoringProfile(), meaningScores())
        val due = result.slots.entries.first { it.key.json == "due_date" }.value
        // The slot's own decision is unchanged ...
        assertThat(due.normalized).isEqualTo("2026-10-15")
        assertThat(due.role).isEqualTo("DUE_DATE")
        // ... and the meaning the reading found for that value is attached to it, not a second value.
        assertThat(due.meaning).isEqualTo("DUE_DATE")
        assertThat(result.slots.entries.first { it.key.json == "total" }.value.meaning).isEqualTo("TOTAL_DUE")
        assertThat(result.slots.entries.firstOrNull { it.key.json == "letter_date" }?.value?.meaning).isNull()
        // Stored in the field's role, in a namespace a slot's own role cannot be in.
        val fields = ExtractionV2Adapter().adapt(result).facts.filter { it.provenance?.slotKey == "due_date" }
        assertThat(fields.single().provenance?.role).isEqualTo("meaning:DUE_DATE")
        assertThat(ValueMeanings.fromRole(fields.single().provenance?.role)?.id).isEqualTo("DUE_DATE")
    }

    @Test
    fun `a date whose meaning contradicts the due-date slot leaves the slot empty`() {
        // The model chose 15.10.2026 for the due date but says it is an appointment: the meaning vetoes the binding (the slot is empty and
        // no rule gives the date to another slot); the amounts are untouched.
        val (result, _) = run(Letters.invoice, ScoringProfile(), meaningScores(dateMeaning = appointment))
        assertThat(result.slots.keys.map { it.json }).doesNotContain("due_date")
        assertThat(result.slots.entries.first { it.key.json == "total" }.value.meaning).isEqualTo("TOTAL_DUE")
    }

    @Test
    fun `a meaning that does not beat its baseline by the margin is other, which is no meaning`() {
        // The made-up date scores as high as the real one: nothing says what the date is.
        val (same, _) = run(Letters.invoice, ScoringProfile(), meaningScores(baseline = 5.0))
        assertThat(same.slots.values.mapNotNull { it.meaning }).isEmpty()
        // A margin wider than the gap does the same.
        val (wide, _) = run(Letters.invoice, ScoringProfile(defaultMeaningMargin = 20.0), meaningScores())
        assertThat(wide.slots.values.mapNotNull { it.meaning }).isEmpty()
        // The slot's decision is the same either way.
        assertThat(wide.slots.entries.first { it.key.json == "due_date" }.value.normalized).isEqualTo("2026-10-15")
    }

    @Test
    fun `the meaning questions are batched per value in the open session and their cost is a fixed number of statements`() {
        val (result, session) = run(Letters.invoice, ScoringProfile(), meaningScores())
        val dates = ValueMeanings.DEFAULT.of(MeaningKind.DATE).size
        val amounts = ValueMeanings.DEFAULT.of(MeaningKind.AMOUNT).size
        val meaningBatches = session.scored.filter { batch -> batch.any { q -> ValueMeanings.DEFAULT.all.any { q.contains(" ${it.description}? Answer:") } } }
        // One batch per kept date or amount and one baseline batch per kind and zone block, each holding every meaning of its kind.
        assertThat(meaningBatches.map { it.size }.toSet()).isEqualTo(setOf(dates, amounts))
        val keptDates = result.slots.keys.count { it.kind.name == "DATE" || it.kind.name == "DEADLINE" }
        val keptAmounts = result.slots.keys.count { it.kind.name == "AMOUNT" }
        assertThat(meaningBatches.size).isAtLeast(keptDates + keptAmounts)
    }
}
