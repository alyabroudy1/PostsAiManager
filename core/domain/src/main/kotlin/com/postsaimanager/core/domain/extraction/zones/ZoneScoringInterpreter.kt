package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.AnswerReader
import com.postsaimanager.core.domain.extraction.v2.AskRecord
import com.postsaimanager.core.domain.extraction.v2.DocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.InterpretationOutcome
import com.postsaimanager.core.domain.extraction.v2.InterpretationRequest
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.Question
import com.postsaimanager.core.domain.extraction.v2.QuestionnairePrompt
import com.postsaimanager.core.domain.extraction.v2.RawExtra
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
import java.util.Locale

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
 * The confidence of a slot or party is derived from its scores ([ScoringProfile.confidence]): the winner's margin over
 * the runner-up and its own score, mapped to LOW, MEDIUM or HIGH by cut points that are data, fitted on recordings.
 *
 * What needs writing is asked in the same open body session, each ask with its own small grammar: the letter's language
 * (BCP-47, one short ask), the naming of each extra ([extras]: which values no slot or party took is decided by score, the
 * words the letter prints next to it and an english key are written), and the free text ([ZoneFreeText]: title, subject
 * line, summary, suggested questions), which runs after the reading exactly as in the other interpreters. One combined
 * generation for language and extras was measured on the device first and a 0.8B model answered it with noise (it copied
 * the example, repeated its last entry, or answered "yes"), which is why the decision is a score and the writing is small.
 */
class ZoneScoringInterpreter(
    private val engine: AiEngine,
    private val session: PromptSession,
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    private val contextTokens: Int,
    private val matcher: TemplateMatcher = TemplateMatcher(),
    private val profile: ScoringProfile = ScoringProfile(),
    /** As [ZoneInterpreter]'s: a labelled, context-only glimpse of the zones just above and below the one asked about. */
    private val neighbourContext: Boolean = false,
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

    /** The writing session is open (the language, the extras' names and the free text are asked in it). */
    private var writingOpen = false
    private var failures = 0

    /** The zones whose text the open session's prefix holds (none in the header session). */
    private var inPrefix: Set<LetterZone> = emptySet()

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
        writingOpen = false
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
        val deferredParties = ArrayList<Pair<String, PartyRole>>()

        // ── header session: the header zones are the prefix ──
        val headerParties = partyNames.filter { isHeader(it.first) }
        val headerSlots = Slots.CORE.filter { isHeader(QuestionNames.slot(it.json), it) }
        if (headerParties.isNotEmpty() || headerSlots.isNotEmpty()) {
            // The prefix is the instructions only: each question carries its own zone (and, with neighbour context,
            // a glimpse of the zones around it), so a zone is judged on its own text and hint.
            val (head, closing) = setup.frame(system, ZonePrompt.HEADER_USER)
            open(head, closing)
            inPrefix = emptySet()
            // A party whose header zones offered no name is asked again with the body, on the zones the registry names
            // as its fallback (the sender's name is sometimes only in the footer).
            headerParties.forEach { if (!party(setup, s, it.first, it.second, widen = false)) deferredParties += it }
            headerSlots.forEach { if (!slot(setup, s, it, widen = false)) deferred += it }
        }

        // ── body session: body, footer and the header's summary are the prefix ──
        val zonesInPrefix = (setup.bodyZones + zoned.mapped(LetterZone.FOOTER)).distinct()
        var budget = ZoneSetup.bodyBudgetChars(contextTokens)
        var opened = false
        val summary = ZonePrompt.summary(s.established)
        var bodyUser = ""
        for (attempt in 0 until MAX_OPEN_ATTEMPTS) {
            bodyUser = ZonePrompt.bodyUser(summary, zonedText(setup, zonesInPrefix, budget))
            val (head, closing) = setup.frame(system, bodyUser)
            if (tryOpen(head, closing)) {
                opened = true
                break
            }
            budget /= 2
        }
        if (!opened) throw Abort("the model could not read the letter")
        inPrefix = zonesInPrefix.toSet()

        val type = scoreType()
        val docType = schema.type(type.first) ?: throw Abort("no document type scored")
        (partyNames.filter { !isHeader(it.first) } + deferredParties).forEach { (name, role) -> party(setup, s, name, role, widen = true) }
        val bodySlots = (Slots.CORE + docType.slots).distinct().filter { !isHeader(QuestionNames.slot(it.json), it) }
        (bodySlots + deferred).forEach { slot(setup, s, it, widen = true) }

        // Everything that is decided is decided; what is left is writing. The scoring session's instruction is "answer Yes or No"
        // and a small model obeys it over any question (measured: the language, the title, the summary were all "Yes"), so the
        // writing has its own session: the same letter under an instruction that only says to write what is asked.
        val picked = pickExtras(setup, s)
        // The letter as plain text: no zone hints and no summary of the header, which a small model copies instead of the letter.
        writingOpen = switchToWriting(setup, ZonePrompt.bodyUser("", zoned.render(zonesInPrefix, budget)))
        val language = if (writingOpen) ask(QuestionnairePrompt.language())?.let { AnswerReader.language(it) } else null
        return RawInterpretation(
            type = type.first, typeConfidence = type.second, language = language,
            parties = s.parties.take(StructuredGrammar.MAX_PARTIES), slots = s.slots, extras = if (writingOpen) nameExtras(setup, picked) else emptyList(),
        )
    }

    /** A value picked as an extra: the candidate and the score that picked it. */
    private class Picked(val candidate: Candidate, val score: Double)

    /**
     * Decides the extras: values of the extras zones that no slot or party took and that the model scores as an important fact of
     * the letter ([ScoringDescriptions.EXTRA]), the best [StructuredGrammar.MAX_EXTRAS] above the `extras` threshold. A score, as
     * for every other value. Names are never offered: the parties were scored already, and the names the extractor finds that are
     * not a party are mostly labels and fragments of lines ("Fällig am", "Betrag €"). A failed scoring leaves no extras.
     */
    private suspend fun pickExtras(setup: ZoneSetup, s: State): List<Picked> {
        val zoned = setup.zoned
        val zones = setup.plan.zones(QuestionNames.EXTRAS_SCORED).filter { zoned.hasText(it) }
        if (zones.isEmpty()) return emptyList()
        val taken = s.parties.map { it.id }.toSet() + s.slots.values.flatMap { listOfNotNull(it.id) + it.ids }
        val cands = zoned.candidatesIn(zones).rows.map { it.candidate }.filter { it.id !in taken && it.kind != CandidateKind.NAME }
        if (cands.isEmpty()) return emptyList()
        val block = block(setup, zones)
        val scores = scoreBatch(
            ScoringDescriptions.EXTRAS_ASK,
            cands.map { block + ZonePrompt.scoringQuestion(it.raw.replace('\n', ' '), zoned.context(it), ScoringDescriptions.EXTRA) },
        ) ?: return emptyList()
        val threshold = profile.threshold(ScoringDescriptions.EXTRAS_ASK)
        return scores.indices.filter { scores[it] > threshold }.sortedByDescending { scores[it] }
            .take(StructuredGrammar.MAX_EXTRAS).map { Picked(cands[it], scores[it]) }
    }

    /**
     * Names each picked extra with one short constrained ask: the words the letter prints next to it. The value is the candidate
     * itself: the model names it and cannot change it, and the verifier treats the extra as it does any other (an id already used, a
     * weak kind, a duplicate by label). The key is the candidate's own kind (`reference`, `amount`, `date`, `phone` ...): a
     * coarse grouping that code can state from the value's shape, where a 0.8B model's own key was the format's placeholder every
     * time. A failed naming leaves that extra out.
     */
    private suspend fun nameExtras(setup: ZoneSetup, picked: List<Picked>): List<RawExtra> = picked.mapNotNull { p ->
        val c = p.candidate
        val label = ask(ZonePrompt.extraName(c.raw.replace('\n', ' '), setup.zoned.context(c)))?.let { AnswerReader.line(it) }?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        RawExtra(label = label, key = c.kind.name.lowercase(), id = c.id, value = "", confidence = confidenceOf(p.score, null, 1).first)
    }

    /** Reopens the session for writing: the same letter, under [ZonePrompt.writingSystem] instead of the scoring instruction. */
    private suspend fun switchToWriting(setup: ZoneSetup, bodyUser: String): Boolean {
        val (head, closing) = setup.frame(ZonePrompt.writingSystem(setup.template), bodyUser)
        return tryOpen(head, closing)
    }

    private fun zonedText(setup: ZoneSetup, zones: List<LetterZone>, budget: Int): String {
        val hints = zones.filter { setup.zoned.hasText(it) }.joinToString("\n") { "ZONE ${it.tag}. HINT: ${setup.plan.hint(it)}" }
        return hints + "\n" + setup.zoned.render(zones, budget)
    }

    /** The zones a question is about, each with its hint and (unless the session's prefix holds it) its text, and the glimpse when asked for. No candidates: nothing is offered as an option. */
    private fun block(setup: ZoneSetup, zones: List<LetterZone>): String {
        val fresh = zones.filter { it !in inPrefix }
        val glimpse = if (neighbourContext && fresh.isNotEmpty()) setup.zoned.glimpse(fresh).takeIf { it.before != null || it.after != null } else null
        return ZonePrompt.zoneBlock(zones, setup.plan::hint, setup.zoned::zoneText, inPrefix, candidates = null, glimpse = glimpse)
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

    /**
     * Scores the names of the zones [name] is asked on; with [widen] (body session only) and no name there, the zones the
     * registry gives as the party's fallback ([SlotPlacements.partyFallback]).
     *
     * @return whether any name was scored (false: nothing was offered, so the caller may ask again where the fallback is).
     */
    private suspend fun party(setup: ZoneSetup, s: State, name: String, role: PartyRole, widen: Boolean): Boolean {
        val zoned = setup.zoned
        var zones = setup.plan.zones(name).filter { zoned.hasText(it) }
        // Every name of the zone is scored, whatever was decided before: what is scored then does not depend on the
        // thresholds, which is what lets a recording be re-decided offline. The one exclusion code makes (the sender is
        // not also the addressee, the routing person or the mailbox) is applied to the choice.
        var cands = zoned.candidatesIn(zones).rows.map { it.candidate }.filter { it.kind == CandidateKind.NAME }
        if (cands.isEmpty() && widen) {
            zones = SlotPlacements.partyFallback(name).map { zoned.mapped(it) }.distinct().filter { zoned.hasText(it) }
            cands = zoned.candidatesIn(zones).rows.map { it.candidate }.filter { it.kind == CandidateKind.NAME }
        }
        if (zones.isEmpty() || cands.isEmpty()) return false
        val what = ScoringDescriptions.ofRole(name)
        val block = block(setup, zones)
        val questions = cands.map { block + ZonePrompt.scoringQuestion(it.raw.replace('\n', ' '), zoned.context(it), what) }
        val scores = scoreBatch(name, questions) ?: return true
        val allowed = scores.indices.filter { role == PartyRole.SENDER || cands[it].id != s.senderId }
        val ranked = allowed.sortedByDescending { scores[it] }
        val best = ranked.firstOrNull() ?: return true
        val threshold = profile.threshold(name)
        if (scores[best] <= threshold) return true
        val (confidence, note) = confidenceOf(scores[best], ranked.getOrNull(1)?.let { scores[it] }, ranked.size)
        val c = cands[best]
        val ctx = zoned.context(c)
        val printed = c.raw.replace('\n', ' ')

        val kinds = scoreBatch("kind:$name", ScoringDescriptions.KINDS.map { (_, statement) -> block + ZonePrompt.scoringQuestion(printed, ctx, statement) })
        val kind = kinds?.let { ks -> ScoringDescriptions.KINDS[ks.indices.maxByOrNull { ks[it] } ?: 0].first } ?: "OTHER"
        var relation: String? = null
        if (role == PartyRole.ADDRESSEE) {
            val h = scoreBatch("household", listOf(block + ZonePrompt.scoringQuestion(printed, ctx, ScoringDescriptions.HOUSEHOLD)))
            relation = if (h != null && h.first() > 0.0) "HOUSEHOLD" else "NONE"
        }
        if (role == PartyRole.SENDER) s.senderId = c.id
        if (role == PartyRole.SENDER || role == PartyRole.ADDRESSEE) {
            s.established += (if (role == PartyRole.SENDER) "sender" else "addressee") to "${c.id} «$printed»"
        }
        s.parties += RawParty(
            role = role.name, id = c.id, kind = kind, relation = relation,
            confidence = confidence, name = null, scoreNote = note,
        )
        return true
    }

    /**
     * The confidence word of a scored answer and the raw numbers behind it. The margin is the winner's score over the
     * runner-up's; with a single candidate there is no runner-up and the margin is over 0.0, where the model is
     * indifferent between Yes and No.
     */
    private fun confidenceOf(best: Double, runnerUp: Double?, candidates: Int): Pair<String, String> {
        val margin = best - (runnerUp ?: 0.0)
        val note = String.format(Locale.ROOT, "score margin %+.2f (winner %+.2f of %d)", margin, best, candidates)
        return profile.confidence(margin, best) to note
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
        var asked = zones
        if (cands.isEmpty() && widen) {
            cands = zoned.offered.rows.map { it.candidate }.filter { it.kind in slot.kind.candidates }
            asked = cands.flatMap { zoned.zonesOfCandidate(it.id) }.distinct().filter { zoned.hasText(it) }
        }
        if (cands.isEmpty()) return false
        val what = ScoringDescriptions.ofSlot(slot)
        val block = block(setup, asked)
        val scores = scoreBatch(name, cands.map { block + ZonePrompt.scoringQuestion(it.raw.replace('\n', ' '), zoned.context(it), what) }) ?: return true
        val threshold = profile.threshold(name)
        val order = scores.indices.sortedByDescending { scores[it] }
        val best = order.first()
        if (scores[best] <= threshold) return true
        val (confidence, note) = confidenceOf(scores[best], order.getOrNull(1)?.let { scores[it] }, order.size)
        s.slots[slot.json] = when (slot.kind) {
            SlotKind.AMOUNT, SlotKind.DATE, SlotKind.DEADLINE ->
                RawSlot(id = cands[best].id, role = roleOf(slot), confidence = confidence, scoreNote = note)
            SlotKind.REFERENCE_LIST -> RawSlot(
                ids = order.filter { scores[it] > threshold }.take(StructuredGrammar.MAX_REF_IDS).map { cands[it].id },
                confidence = confidence, scoreNote = note,
            )
            else -> RawSlot(id = cands[best].id, confidence = confidence, scoreNote = note)
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

    /** One generated answer, asked in the writing session (see [switchToWriting]), or null when the engine failed it. */
    private suspend fun ask(question: Question): String? {
        val started = System.nanoTime()
        val result = session.ask("\n\n" + question.text + tail, question.grammar, question.maxTokens)
        val answer = (result as? PamResult.Success)?.data?.trim()
        records += AskRecord(question.name, question.text, answer, (System.nanoTime() - started) / NANOS_PER_MS)
        return answer
    }

    override suspend fun writeText(request: TextRequest): TextOutcome {
        if (!writingOpen) return TextOutcome.Failed("the letter was not read")
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
