package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.AnswerReader
import com.postsaimanager.core.domain.extraction.v2.AskRecord
import com.postsaimanager.core.domain.extraction.v2.DocType
import com.postsaimanager.core.domain.extraction.v2.DocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.InterpretationOutcome
import com.postsaimanager.core.domain.extraction.v2.InterpretationRequest
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.PartyAnswer
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.Question
import com.postsaimanager.core.domain.extraction.v2.QuestionGrammars
import com.postsaimanager.core.domain.extraction.v2.QuestionnairePrompt
import com.postsaimanager.core.domain.extraction.v2.RawExtra
import com.postsaimanager.core.domain.extraction.v2.RawInterpretation
import com.postsaimanager.core.domain.extraction.v2.RawParty
import com.postsaimanager.core.domain.extraction.v2.RawSlot
import com.postsaimanager.core.domain.extraction.v2.SlotKey
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.domain.extraction.v2.StructuredGrammar
import com.postsaimanager.core.domain.extraction.v2.TextOutcome
import com.postsaimanager.core.domain.extraction.v2.TextRequest

/**
 * "Layout template, then zone by zone": the same [DocumentInterpreter] as the questionnaire, producing the
 * same [RawInterpretation], but each question is asked about the *zone* of the letter it belongs to.
 *
 * The page is matched to a layout class from geometry ([TemplateMatcher]). Every question then brings the
 * text of its zone, a hint that says what such a block usually is, and only the candidates printed in
 * that zone, so the model chooses among two or three values instead of the whole letter's table (the
 * measured failure of the questionnaire: with the whole table in view a small model mostly picked the first
 * option). Two sessions:
 *  - **header**: the instructions only. Each question adds its own short zone text (letterhead, return line,
 *    address field, reference block).
 *  - **body**: re-opened with the body text as the prefix, so the many questions about it do not decode it
 *    again. It starts with a summary of what the header established, and the document type is asked first.
 *
 * AI decides, code verifies. A hint is a prior: an answer that contradicts the zone it was asked on (a name
 * that is not in the address field, as addressee) is allowed but carries a note, and the verifier caps it.
 * The only exclusion code makes is the invariant that the sender is not also the addressee.
 */
class ZoneInterpreter(
    private val engine: AiEngine,
    private val session: PromptSession,
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    private val contextTokens: Int,
    private val matcher: TemplateMatcher = TemplateMatcher(),
    private val measureTokens: Boolean = false,
) : DocumentInterpreter {

    override val maxAnswerTokens: Int = QuestionnairePrompt.QUESTION_RESERVE_TOKENS
    override val maxTextTokens: Int = QuestionnairePrompt.QUESTION_RESERVE_TOKENS

    val transcript: List<AskRecord> get() = records
    private val records = ArrayList<AskRecord>()

    /** Tokens and milliseconds spent reading prefixes (header and body sessions together) in the last [interpret]. */
    var prefixTokens: Int = 0
        private set
    var prefixMs: Long = 0
        private set

    /** The template the last letter matched, and how well. */
    var templateId: String = ""
        private set
    var templateScore: Float = 0f
        private set

    private var tail = ""
    private var bodyOpen = false
    private var consecutiveFailures = 0

    override fun promptOverheadChars(offered: OfferedCandidates): Int = OVERHEAD_CHARS

    override fun textOverheadChars(): Int = OVERHEAD_CHARS

    override suspend fun countTokens(text: String): Int? = session.countTokens(text)

    private class Abort(val reason: String) : Exception(reason)

    override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome {
        records.clear()
        consecutiveFailures = 0
        bodyOpen = false
        prefixTokens = 0
        prefixMs = 0
        val layout = request.layout
            ?: return InterpretationOutcome.Failed("zones need the zoned layout", null, "", "")
        val setup = ZoneSetup(engine, layout, request.offered, matcher, request.pageAspect)
        templateId = setup.template.id
        templateScore = setup.match.score
        return try {
            InterpretationOutcome.Answered(read(setup, request.offered), transcriptText(), ZonePrompt.system(setup.template), "")
        } catch (e: Abort) {
            session.close()
            InterpretationOutcome.Failed(e.reason, transcriptText().take(FAILED_RAW_CHARS), ZonePrompt.system(setup.template), "")
        }
    }

    // ── one question of the plan ────────────────────────────────────────────────

    /** A question of the plan: its name, where it is asked, and how its question is made from the zone's candidates. */
    private class Step(
        val name: String,
        val zones: List<LetterZone>,
        val slot: SlotKey? = null,
        val candidates: (OfferedCandidates) -> OfferedCandidates = { it },
        val build: (OfferedCandidates) -> Question?,
    )

    private class Answers {
        val parties = ArrayList<RawParty>()
        val slots = LinkedHashMap<String, RawSlot>()
        val taken = LinkedHashSet<String>()
        var senderId: String? = null
        val established = ArrayList<Pair<String, String>>()
        var type: com.postsaimanager.core.domain.extraction.v2.TypeAnswer? = null
    }

    private suspend fun read(setup: ZoneSetup, offered: OfferedCandidates): RawInterpretation {
        val plan = setup.plan
        val zoned = setup.zoned
        val a = Answers()
        val nameIds = offered.idsOf(CandidateKind.NAME)

        fun party(name: String, withRelation: Boolean, list: Boolean, base: (OfferedCandidates) -> Question, excludeSender: Boolean = false) =
            Step(name, plan.zones(name), candidates = { it.only(CandidateKind.NAME) }, build = { c ->
                val q = base(c)
                // The zone's candidates are what the model is shown; any name id is allowed in the answer, and one
                // from outside the zone is noted (see [zoneNote]) rather than forbidden.
                val ids = if (excludeSender) nameIds.filter { it != a.senderId } else nameIds
                Question(q.name, q.text, QuestionGrammars.party(ids, withRelation, list), q.maxTokens)
            })

        val sender = party(QuestionNames.SENDER, false, false, { QuestionnairePrompt.sender(it) })
        val addressee = party(QuestionNames.ADDRESSEE, true, true, { QuestionnairePrompt.addressee(it, a.senderId) }, excludeSender = true)
        val careOf = party(QuestionNames.CARE_OF, false, false, { QuestionnairePrompt.careOf(it) })
        val contact = party(QuestionNames.CONTACT, false, false, { QuestionnairePrompt.contactPerson(it) })
        val subjectPerson = party(QuestionNames.SUBJECT_PERSON, false, true, { QuestionnairePrompt.subjectPerson(it) })
        val partySteps = listOf(sender, addressee, careOf, contact, subjectPerson)

        fun slotStep(slot: SlotKey) = Step(
            QuestionNames.slot(slot.json), plan.zones(QuestionNames.slot(slot.json), slot), slot,
            candidates = { it.only(*slot.kind.candidates) },
            build = { c -> QuestionnairePrompt.slot(slot, c) },
        )

        // ── header session ──
        val deferred = ArrayList<Step>()
        val headerSteps = (partySteps + Slots.CORE.map { slotStep(it) }).filter { plan.isHeader(it.zones) }
        if (headerSteps.isNotEmpty()) {
            val (head, closing) = setup.frame(ZonePrompt.system(setup.template), ZonePrompt.HEADER_USER)
            open(head, closing)
            for (step in headerSteps) {
                // A slot whose header zones held no candidate of its kind is asked again with the body (see [run]).
                if (!run(step, setup, a, inPrefix = emptySet(), widen = false) && step.slot != null) deferred += step
            }
        }

        // ── body session: the body text is the prefix ──
        var budget = ZoneSetup.bodyBudgetChars(contextTokens)
        val summary = ZonePrompt.summary(a.established)
        var opened = false
        for (attempt in 0 until MAX_OPEN_ATTEMPTS) {
            val bodyText = zoned.render(setup.bodyZones, budget)
            val (head, closing) = setup.frame(ZonePrompt.system(setup.template), ZonePrompt.bodyUser(summary, bodyText))
            if (tryOpen(head, closing)) {
                opened = true
                break
            }
            budget = budget / 2
        }
        if (!opened) throw Abort("the model could not read the letter")
        bodyOpen = true
        val inPrefix = setup.bodyZones.toSet()

        val typeZones = plan.zones(QuestionNames.TYPE).ifEmpty { setup.bodyZones }
        val typeText = ZonePrompt.zoneBlock(typeZones, plan::hint, zoned::zoneText, inPrefix + typeZones, candidates = null)
        val typeQuestion = QuestionnairePrompt.type(schema)
        a.type = AnswerReader.type(ask(QuestionNames.TYPE, typeText + typeQuestion.text, typeQuestion).orEmpty())
            ?: throw Abort("the model gave no document type")
        val docType: DocType = schema.type(a.type!!.typeId)
            ?: throw Abort("the model chose a document type that does not exist: ${a.type!!.typeId}")

        val bodyPartySteps = partySteps.filter { !plan.isHeader(it.zones) }
        val slotSteps = (Slots.CORE + docType.slots).distinct().map { slotStep(it) }.filter { !plan.isHeader(it.zones) }
        for (step in bodyPartySteps + slotSteps + deferred) run(step, setup, a, inPrefix, widen = step.slot != null)

        val extrasZones = plan.zones(QuestionNames.EXTRAS)
        val extrasCandidates = zoned.candidatesIn(extrasZones)
        if (extrasZones.any { zoned.hasText(it) } && extrasCandidates.size > 0) {
            val q = QuestionnairePrompt.extras(extrasCandidates, a.taken)
            val text = ZonePrompt.zoneBlock(extrasZones.filter { zoned.hasText(it) }, plan::hint, zoned::zoneText, inPrefix, extrasCandidates) + q.text
            val extras: List<RawExtra> = AnswerReader.extras(ask(q.name, text, q).orEmpty()).take(StructuredGrammar.MAX_EXTRAS)
            return finish(a, extras)
        }
        return finish(a, emptyList())
    }

    private fun finish(a: Answers, extras: List<RawExtra>) = RawInterpretation(
        type = a.type!!.typeId,
        typeConfidence = a.type!!.confidence,
        language = a.type!!.language,
        parties = a.parties.take(StructuredGrammar.MAX_PARTIES),
        slots = a.slots,
        extras = extras,
    )

    /**
     * Asks one step of the plan on its zones and files the answer.
     *
     * @param widen the placement is a prior: when the zones it names hold no candidate of the slot's kind,
     *   the slot is asked where its candidates are (their own zones), instead of not at all. Only in the body
     *   session, where the zones with long text are already the prefix.
     * @return whether a question was asked.
     */
    private suspend fun run(step: Step, setup: ZoneSetup, a: Answers, inPrefix: Set<LetterZone>, widen: Boolean): Boolean {
        val zoned = setup.zoned
        var zones = step.zones.filter { zoned.hasText(it) }
        var cands = step.candidates(zoned.candidatesIn(zones))
        if (cands.size == 0 && widen && step.slot != null) {
            val everywhere = step.candidates(zoned.offered)
            if (everywhere.size > 0) {
                cands = everywhere
                zones = everywhere.rows.flatMap { zoned.zonesOfCandidate(it.candidate.id) }.distinct().filter { zoned.hasText(it) }
            }
        }
        if (zones.isEmpty()) return false
        val question = step.build(cands) ?: return false
        val text = ZonePrompt.zoneBlock(zones, setup.plan::hint, zoned::zoneText, inPrefix, cands) + question.text
        val answer = ask(question.name, text, question).orEmpty()
        when (step.name) {
            QuestionNames.SENDER -> AnswerReader.parties(answer, withRelation = false).firstOrNull()?.let { addParty(a, PartyRole.SENDER, it, zones, cands, zoned) }
            QuestionNames.ADDRESSEE -> AnswerReader.parties(answer, withRelation = true)
                .filter { it.id != a.senderId }.take(QuestionGrammars.MAX_ADDRESSEES)
                .forEachIndexed { i, p -> addParty(a, if (i == 0) PartyRole.ADDRESSEE else PartyRole.CO_ADDRESSEE, p, zones, cands, zoned) }
            QuestionNames.SUBJECT_PERSON -> AnswerReader.parties(answer, withRelation = false).take(QuestionGrammars.MAX_ADDRESSEES)
                .forEach { addParty(a, PartyRole.SUBJECT_PERSON, it, zones, cands, zoned) }
            QuestionNames.CONTACT -> AnswerReader.parties(answer, withRelation = false).firstOrNull()?.let { addParty(a, PartyRole.ROUTING, it, zones, cands, zoned) }
            QuestionNames.CARE_OF -> AnswerReader.parties(answer, withRelation = false).firstOrNull()?.let { addParty(a, PartyRole.CARE_OF, it, zones, cands, zoned) }
            else -> step.slot?.let { slot ->
                AnswerReader.slot(slot, answer)?.let { raw ->
                    a.slots[slot.json] = raw
                    raw.id?.let { if (zoned.offered.get(it) != null) a.taken += it }
                    raw.ids.forEach { if (zoned.offered.get(it) != null) a.taken += it }
                }
            }
        }
        return true
    }

    private fun addParty(a: Answers, role: PartyRole, p: PartyAnswer, zones: List<LetterZone>, cands: OfferedCandidates, zoned: ZonedLetter) {
        if (zoned.offered.get(p.id) != null) a.taken += p.id
        if (role == PartyRole.SENDER) a.senderId = p.id
        val printed = zoned.offered.get(p.id)?.raw ?: p.id
        if (role == PartyRole.SENDER || role == PartyRole.ADDRESSEE) {
            a.established += (if (role == PartyRole.SENDER) "sender" else "addressee") to "${p.id} «${p.name.ifBlank { printed }}»"
        }
        a.parties += RawParty(
            role = role.name, id = p.id, kind = p.kind, relation = p.relation, confidence = p.confidence,
            name = p.name.ifBlank { null }, zoneNote = zoneNote(role, p.id, zones, cands, zoned),
        )
    }

    /**
     * A note when the answer contradicts the zone it was asked on: a candidate id that was not among the zone's
     * candidates, or a written name that does not occur in the zone's text. Null when it agrees.
     */
    private fun zoneNote(role: PartyRole, id: String, zones: List<LetterZone>, cands: OfferedCandidates, zoned: ZonedLetter): String? {
        val where = zones.joinToString(" / ") { it.tag }
        val outside = if (zoned.offered.get(id) != null) {
            cands.get(id) == null
        } else {
            val key = ZonedLetter.squash(id)
            key.isNotEmpty() && zones.none { ZonedLetter.squash(zoned.zoneText(it)).contains(key) }
        }
        return if (outside) "$role '${id.take(40)}' is not in the $where zone, where this block usually holds it" else null
    }

    private fun OfferedCandidates.only(vararg kinds: CandidateKind): OfferedCandidates {
        val ids = idsOf(*kinds).toSet()
        return OfferedCandidates(rows.filter { it.candidate.id in ids })
    }

    // ── sessions ────────────────────────────────────────────────────────────────

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

    /** The answer, or null when the engine failed the question. Three failures in a row abort the reading. */
    private suspend fun ask(name: String, text: String, question: Question): String? {
        val started = System.nanoTime()
        val result = session.ask("\n\n" + text + tail, question.grammar, question.maxTokens)
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        val answer = (result as? PamResult.Success)?.data?.trim()
        records += AskRecord(
            name = name, question = text, answer = answer, ms = ms,
            questionTokens = if (measureTokens) session.countTokens(text + tail) ?: -1 else -1,
            answerTokens = if (measureTokens && answer != null) session.countTokens(answer) ?: -1 else -1,
        )
        if (answer == null) {
            if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) throw Abort("the engine failed $MAX_CONSECUTIVE_FAILURES questions in a row")
        } else {
            consecutiveFailures = 0
        }
        return answer
    }

    override suspend fun writeText(request: TextRequest): TextOutcome {
        if (!bodyOpen) return TextOutcome.Failed("the letter was not read")
        consecutiveFailures = 0
        try {
            val out = ZoneFreeText.write(request) { q ->
                try {
                    ask("text:" + q.name.removePrefix("text:"), q.text, q)
                } catch (e: Abort) {
                    null
                }
            }
            return (out as? TextOutcome.Written)?.let {
                TextOutcome.Written(it.text, records.filter { r -> r.name.startsWith("text:") }.joinToString("\n") { r -> "${r.name}: ${r.answer}" })
            } ?: out
        } finally {
            session.close()
        }
    }

    private fun transcriptText(): String = records.joinToString("\n") { "${it.name}: ${it.answer}" }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MAX_OPEN_ATTEMPTS = 3
        private const val FAILED_RAW_CHARS = 300
        private const val OVERHEAD_CHARS = 700
    }
}
