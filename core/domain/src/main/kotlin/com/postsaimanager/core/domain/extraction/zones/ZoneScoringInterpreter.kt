package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.AskRecord
import com.postsaimanager.core.domain.extraction.v2.DocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.InterpretationOutcome
import com.postsaimanager.core.domain.extraction.v2.InterpretationRequest
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.Question
import com.postsaimanager.core.domain.extraction.v2.QuestionnairePrompt
import com.postsaimanager.core.domain.extraction.v2.RawInterpretation
import com.postsaimanager.core.domain.extraction.v2.RawParty
import com.postsaimanager.core.domain.extraction.v2.RawSlot
import com.postsaimanager.core.domain.extraction.v2.Roles
import com.postsaimanager.core.domain.extraction.v2.SlotKey
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.domain.extraction.v2.StructuredGrammar
import com.postsaimanager.core.domain.extraction.v2.TextOutcome
import com.postsaimanager.core.domain.extraction.v2.TextRequest

/**
 * Label-free reading, zone by zone: the model is never shown a list of options, so it cannot prefer the
 * first one. Each question is turned into a yes/no judgement per candidate ("Is «X» (context: the line it
 * is printed on, above and below) the addressee?") and the model's own next-token log-odds of Yes against
 * No ([PromptSession.score]) are read for every candidate of the zone. The best candidate is taken when its
 * score is above the question's abstain threshold ([ScoringProfile]), otherwise the answer is NONE.
 *
 * The same layout template, zones and two sessions as [ZoneInterpreter] (the header zones once as the header
 * session's prefix; the body, footer and summary of the header as the body session's), so the model reads each
 * zone once. Nothing is generated except the free text at the end; no grammar is needed because a score is
 * a number.
 *
 * AI decides, code verifies: the argmax is the model's own preference among values the extractor found; the
 * verifier checks the result exactly as before. Roles of amounts and dates come from the slot the candidate won
 * (the slot says what the value is), a party's kind and household relation are scored the same way.
 *
 * Not produced here: extras (metadata no slot covers) and the type's language. They need generation.
 */
class ZoneScoringInterpreter(
    private val engine: AiEngine,
    private val session: PromptSession,
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    private val contextTokens: Int,
    private val matcher: TemplateMatcher = TemplateMatcher(),
    private val profile: ScoringProfile = ScoringProfile(),
) : DocumentInterpreter {

    override val maxAnswerTokens: Int = QuestionnairePrompt.QUESTION_RESERVE_TOKENS
    override val maxTextTokens: Int = QuestionnairePrompt.QUESTION_RESERVE_TOKENS

    val transcript: List<AskRecord> get() = records
    private val records = ArrayList<AskRecord>()

    var prefixTokens: Int = 0
        private set
    var prefixMs: Long = 0
        private set
    var templateId: String = ""
        private set
    var templateScore: Float = 0f
        private set

    private var tail = ""
    private var bodyOpen = false
    private var failures = 0

    override fun promptOverheadChars(offered: OfferedCandidates): Int = OVERHEAD_CHARS

    override fun textOverheadChars(): Int = OVERHEAD_CHARS

    override suspend fun countTokens(text: String): Int? = session.countTokens(text)

    private class Abort(val reason: String) : Exception(reason)

    private class State {
        val parties = ArrayList<RawParty>()
        val slots = LinkedHashMap<String, RawSlot>()
        var senderId: String? = null
        val established = ArrayList<Pair<String, String>>()
    }

    override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome {
        records.clear()
        failures = 0
        bodyOpen = false
        prefixTokens = 0
        prefixMs = 0
        val layout = request.layout ?: return InterpretationOutcome.Failed("zones need the zoned layout", null, "", "")
        val setup = ZoneSetup(engine, layout, request.offered, matcher, request.pageAspect)
        templateId = setup.template.id
        templateScore = setup.match.score
        return try {
            InterpretationOutcome.Answered(read(setup), transcriptText(), ZonePrompt.scoringSystem(setup.template), "")
        } catch (e: Abort) {
            session.close()
            InterpretationOutcome.Failed(e.reason, transcriptText().take(FAILED_RAW_CHARS), ZonePrompt.scoringSystem(setup.template), "")
        }
    }

    private suspend fun read(setup: ZoneSetup): RawInterpretation {
        val plan = setup.plan
        val zoned = setup.zoned
        val system = ZonePrompt.scoringSystem(setup.template)
        val s = State()

        val partyNames = listOf(
            QuestionNames.SENDER to PartyRole.SENDER, QuestionNames.ADDRESSEE to PartyRole.ADDRESSEE, QuestionNames.CARE_OF to PartyRole.CARE_OF,
            QuestionNames.CONTACT to PartyRole.ROUTING, QuestionNames.SUBJECT_PERSON to PartyRole.SUBJECT_PERSON,
        )
        fun isHeader(name: String, slot: SlotKey? = null) = plan.isHeader(plan.zones(name, slot))

        val deferred = ArrayList<SlotKey>()

        // ── header session: the header zones are the prefix ──
        val headerParties = partyNames.filter { isHeader(it.first) }
        val headerSlots = Slots.CORE.filter { isHeader(QuestionNames.slot(it.json), it) }
        if (headerParties.isNotEmpty() || headerSlots.isNotEmpty()) {
            val zones = ZonedLetter.HEADER_ZONES.map { zoned.mapped(it) }.distinct().filter { zoned.hasText(it) }
            val block = ZonePrompt.zoneBlock(zones, plan::hint, zoned::zoneText, emptySet(), null)
            val (head, closing) = setup.frame(system, ZonePrompt.HEADER_USER + "\n" + block)
            open(head, closing)
            headerParties.forEach { (name, role) -> party(setup, s, name, role) }
            headerSlots.forEach { if (!slot(setup, s, it, widen = false)) deferred += it }
        }

        // ── body session: body, footer and the header's summary are the prefix ──
        val zonesInPrefix = (setup.bodyZones + zoned.mapped(LetterZone.FOOTER)).distinct()
        var budget = ZoneSetup.bodyBudgetChars(contextTokens)
        var opened = false
        val summary = ZonePrompt.summary(s.established)
        for (attempt in 0 until MAX_OPEN_ATTEMPTS) {
            val text = zonedText(setup, zonesInPrefix, budget)
            val (head, closing) = setup.frame(system, ZonePrompt.bodyUser(summary, text))
            if (tryOpen(head, closing)) {
                opened = true
                break
            }
            budget /= 2
        }
        if (!opened) throw Abort("the model could not read the letter")
        bodyOpen = true

        val type = scoreType()
        val docType = schema.type(type.first) ?: throw Abort("no document type scored")
        partyNames.filter { !isHeader(it.first) }.forEach { (name, role) -> party(setup, s, name, role) }
        val bodySlots = (Slots.CORE + docType.slots).distinct().filter { !isHeader(QuestionNames.slot(it.json), it) }
        (bodySlots + deferred).forEach { slot(setup, s, it, widen = true) }

        return RawInterpretation(
            type = type.first, typeConfidence = type.second, language = null,
            parties = s.parties.take(StructuredGrammar.MAX_PARTIES), slots = s.slots, extras = emptyList(),
        )
    }

    private fun zonedText(setup: ZoneSetup, zones: List<LetterZone>, budget: Int): String {
        val hints = zones.filter { setup.zoned.hasText(it) }.joinToString("\n") { "ZONE ${it.tag}. HINT: ${setup.plan.hint(it)}" }
        return hints + "\n" + setup.zoned.render(zones, budget)
    }

    // ── the type ──

    private suspend fun scoreType(): Pair<String, String> {
        val types = schema.types.filter { it.description.isNotBlank() }
        val questions = types.map { "Is this document ${it.description}? Answer:" }
        val scores = scoreBatch("type", questions) ?: throw Abort("the type could not be scored")
        val order = scores.indices.sortedByDescending { scores[it] }
        val best = order.first()
        val margin = if (order.size > 1) scores[best] - scores[order[1]] else scores[best]
        return types[best].id to profile.confidence(margin)
    }

    // ── parties ──

    private suspend fun party(setup: ZoneSetup, s: State, name: String, role: PartyRole) {
        val zoned = setup.zoned
        val zones = setup.plan.zones(name).filter { zoned.hasText(it) }
        if (zones.isEmpty()) return
        val cands = zoned.candidatesIn(zones).rows.map { it.candidate }
            .filter { it.kind == CandidateKind.NAME && it.id != s.senderId }
        if (cands.isEmpty()) return
        val what = ScoringDescriptions.ofRole(name)
        val questions = cands.map { ZonePrompt.scoringQuestion(it.raw.replace('\n', ' '), zoned.context(it), what) }
        val scores = scoreBatch(name, questions) ?: return
        val best = scores.indices.maxByOrNull { scores[it] } ?: return
        val threshold = profile.threshold(name)
        if (scores[best] <= threshold) return
        val c = cands[best]
        val ctx = zoned.context(c)
        val printed = c.raw.replace('\n', ' ')

        val kinds = scoreBatch("kind:$name", ScoringDescriptions.KINDS.map { (_, statement) -> ZonePrompt.scoringQuestion(printed, ctx, statement) })
        val kind = kinds?.let { ks -> ScoringDescriptions.KINDS[ks.indices.maxByOrNull { ks[it] } ?: 0].first } ?: "OTHER"
        var relation: String? = null
        if (role == PartyRole.ADDRESSEE) {
            val h = scoreBatch("household", listOf(ZonePrompt.scoringQuestion(printed, ctx, ScoringDescriptions.HOUSEHOLD)))
            relation = if (h != null && h.first() > 0.0) "HOUSEHOLD" else "NONE"
        }
        if (role == PartyRole.SENDER) s.senderId = c.id
        if (role == PartyRole.SENDER || role == PartyRole.ADDRESSEE) {
            s.established += (if (role == PartyRole.SENDER) "sender" else "addressee") to "${c.id} «$printed»"
        }
        s.parties += RawParty(
            role = role.name, id = c.id, kind = kind, relation = relation,
            confidence = profile.confidence(scores[best] - threshold), name = null,
        )
    }

    // ── slots ──

    /**
     * Scores every candidate of [slot]'s kind in the zones the template places it on. The placement is a
     * prior: with [widen] (body session only) and no candidate there, the candidates anywhere are scored.
     *
     * @return whether anything was scored.
     */
    private suspend fun slot(setup: ZoneSetup, s: State, slot: SlotKey, widen: Boolean): Boolean {
        if (slot.kind == SlotKind.ACTION) return true
        val zoned = setup.zoned
        val name = QuestionNames.slot(slot.json)
        val zones = setup.plan.zones(name, slot).filter { zoned.hasText(it) }
        var cands = zoned.candidatesIn(zones).rows.map { it.candidate }.filter { it.kind in slot.kind.candidates }
        if (cands.isEmpty() && widen) cands = zoned.offered.rows.map { it.candidate }.filter { it.kind in slot.kind.candidates }
        if (cands.isEmpty()) return false
        val what = ScoringDescriptions.ofSlot(slot)
        val scores = scoreBatch(name, cands.map { ZonePrompt.scoringQuestion(it.raw.replace('\n', ' '), zoned.context(it), what) }) ?: return true
        val threshold = profile.threshold(name)
        val order = scores.indices.sortedByDescending { scores[it] }
        val best = order.first()
        if (scores[best] <= threshold) return true
        val confidence = profile.confidence(scores[best] - threshold)
        s.slots[slot.json] = when (slot.kind) {
            SlotKind.AMOUNT, SlotKind.DATE, SlotKind.DEADLINE ->
                RawSlot(id = cands[best].id, role = roleOf(slot), confidence = confidence)
            SlotKind.REFERENCE_LIST -> RawSlot(
                ids = order.filter { scores[it] > threshold }.take(StructuredGrammar.MAX_REF_IDS).map { cands[it].id }, confidence = confidence,
            )
            else -> RawSlot(id = cands[best].id, confidence = confidence)
        }
        return true
    }

    /** The role a value has by winning [slot]: the slot's own expected role, in the schema's order. */
    private fun roleOf(slot: SlotKey): String {
        val order = if (slot.kind == SlotKind.AMOUNT) Roles.AMOUNT else Roles.DATE
        return order.firstOrNull { it in slot.expects } ?: "OTHER"
    }

    // ── engine ──

    private suspend fun open(head: String, closing: String) {
        if (!tryOpen(head, closing)) throw Abort("the model could not read the letter")
    }

    private suspend fun tryOpen(head: String, closing: String): Boolean {
        val started = System.nanoTime()
        val opened = session.open(head)
        prefixMs += (System.nanoTime() - started) / NANOS_PER_MS
        if (opened !is PamResult.Success) return false
        prefixTokens += opened.data
        tail = closing
        return true
    }

    /** One score per question, or null when the engine failed the batch. Three failures in a row abort the reading. */
    private suspend fun scoreBatch(name: String, questions: List<String>): List<Double>? {
        if (questions.isEmpty()) return emptyList()
        val started = System.nanoTime()
        val result = session.score(questions.map { "\n\n" + it + tail }, YES, NO)
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        val scores = (result as? PamResult.Success)?.data?.takeIf { it.size == questions.size }
        records += AskRecord(
            name = "score:$name", question = questions.joinToString(BATCH_SEPARATOR), answer = scores?.joinToString(","), ms = ms,
        )
        if (scores == null) {
            if (++failures >= MAX_FAILURES) throw Abort("the engine failed $MAX_FAILURES scorings in a row")
        } else {
            failures = 0
        }
        return scores
    }

    private suspend fun ask(question: Question): String? {
        val started = System.nanoTime()
        val result = session.ask("\n\n" + question.text + tail, question.grammar, question.maxTokens)
        val answer = (result as? PamResult.Success)?.data?.trim()
        records += AskRecord(question.name, question.text, answer, (System.nanoTime() - started) / NANOS_PER_MS)
        return answer
    }

    override suspend fun writeText(request: TextRequest): TextOutcome {
        if (!bodyOpen) return TextOutcome.Failed("the letter was not read")
        try {
            return ZoneFreeText.write(request) { q -> ask(q) }
        } finally {
            session.close()
        }
    }

    private fun transcriptText(): String = records.joinToString("\n") { "${it.name}: ${it.answer}" }

    companion object {
        const val YES = "Yes"
        const val NO = "No"

        /** Separates the questions of one scored batch in a recording. */
        const val BATCH_SEPARATOR = "\n@@\n"

        private const val NANOS_PER_MS = 1_000_000L
        private const val MAX_FAILURES = 3
        private const val MAX_OPEN_ATTEMPTS = 3
        private const val FAILED_RAW_CHARS = 300
        private const val OVERHEAD_CHARS = 700
    }
}
