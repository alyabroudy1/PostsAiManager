package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.address.AddressLineLabeler
import com.postsaimanager.core.domain.extraction.address.AddressReading
import com.postsaimanager.core.domain.extraction.address.StructuredAddressReader
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.actions.ActionKindReader
import com.postsaimanager.core.domain.extraction.text.SummaryFacts
import com.postsaimanager.core.domain.extraction.text.SummaryWriter
import com.postsaimanager.core.domain.extraction.v2.AnswerReader
import com.postsaimanager.core.domain.extraction.v2.AskRecord
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.DocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.Enrichment
import com.postsaimanager.core.domain.extraction.v2.EnrichmentOutcome
import com.postsaimanager.core.domain.extraction.v2.EnrichmentRequest
import com.postsaimanager.core.domain.extraction.v2.DocFamily
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.InterpretationOutcome
import com.postsaimanager.core.domain.extraction.v2.InterpretationRequest
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.Parties
import com.postsaimanager.core.domain.extraction.v2.Party
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.PartyRelation
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.Question
import com.postsaimanager.core.domain.extraction.v2.QuestionnairePrompt
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.domain.extraction.v2.RawAlternative
import com.postsaimanager.core.domain.extraction.v2.RawExtra
import com.postsaimanager.core.domain.extraction.v2.RawInterpretation
import com.postsaimanager.core.domain.extraction.v2.RawParty
import com.postsaimanager.core.domain.extraction.v2.RawSlot
import com.postsaimanager.core.domain.extraction.v2.Roles
import com.postsaimanager.core.domain.extraction.v2.SlotKey
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.extraction.v2.SlotOrigin
import com.postsaimanager.core.domain.extraction.v2.SlotValue
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.domain.extraction.v2.StructuredGrammar
import com.postsaimanager.core.domain.extraction.v2.TextOutcome
import com.postsaimanager.core.domain.extraction.v2.TextRequest
import com.postsaimanager.core.domain.extraction.v2.UnreadText
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.KeySlot
import com.postsaimanager.core.model.TicketSlot
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
 * What a document is comes first: [FamilyClassifier] scores one batch ("Is this document <family>?" for every family, "Does this
 * document concern <topic>?" for every topic) and the family is the argmax above the `family` threshold, else the abstain family; a
 * family a person chose is read as it is. [ExtractionSchema.slotsFor] then names the slots (the family's and those of the best two
 * topics). After the parties are settled, a family with a recipient block has its structured address read ([StructuredAddressReader]:
 * the line labels are scored too, never generated). Every scored question also keeps its runner-up candidates, the alternatives the
 * Edit sheet offers.
 *
 * What needs writing is asked in the same open body session, each ask with its own small grammar: the letter's language
 * (BCP-47, one short ask), the naming of each extra ([extras]: which values no slot or party took is decided by score, the
 * words the letter prints next to it and an english key are written), the subject line and the suggested questions
 * ([ZoneFreeText]), and the summary ([SummaryWriter]: it is given the verified facts and writes one or two sentences, a gate checks
 * them, a template stands in when it cannot). There is no title ask: the title is composed from verified fields. This runs after the
 * reading, as the second stage. One combined generation for language and extras was measured on the device first and a 0.8B model
 * answered it with noise (it copied the example, repeated its last entry, or answered "yes"), which is why the decision is a score and
 * the writing is small.
 *
 * @param topicsInFirstStage whether the topic scores run with the family scores (+14 scores in the first stage); false moves them to the
 *   second stage ([ModelProfile.topicsInFirstStage]), where they are stored but add no slots to a letter already read.
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
    private val topicsInFirstStage: Boolean = true,
    private val addressReader: StructuredAddressReader = StructuredAddressReader(AddressLineLabeler(profile = profile), profile = profile),
) : DocumentInterpreter {

    override val maxAnswerTokens: Int = QuestionnairePrompt.QUESTION_RESERVE_TOKENS
    override val maxTextTokens: Int = QuestionnairePrompt.QUESTION_RESERVE_TOKENS

    val transcript: List<AskRecord> get() = records

    /** Structure only (see [DocumentInterpreter.trace]): the template, the zones, and per question the pick, its zone and its scores. */
    override val trace: List<String> get() = traceLines
    private val traceLines = ArrayList<String>()
    private val records = ArrayList<AskRecord>()

    var prefixTokens: Int = 0
        private set
    var prefixMs: Long = 0
        private set
    var templateId: String = ""
        private set
    var templateScore: Float = 0f
        private set

    override var unread: UnreadText? = null
        private set

    private val decoder: SlotDecoder = profile.decoder.create()

    /** Every question scored in this reading, for the decoder to decide again once all are in (see [redecide]). */
    private val scored = ArrayList<Scored>()

    private var tail = ""

    /** Totals of the scoring in this reading, for the timing summary. */
    private var scoreBatches = 0
    private var scoreCount = 0
    private var scoreMs = 0L

    /** The letter's session, open and ready for the second stage; null before the first stage has read the letter. */
    private var letter: LetterSession? = null
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
        scored.clear()
        traceLines.clear()
        scoreBatches = 0
        scoreCount = 0
        scoreMs = 0
        unread = null
        failures = 0
        letter = null
        ownSlots = emptySet()
        prescored.clear()
        prefixTokens = 0
        prefixMs = 0
        val layout = request.layout ?: return InterpretationOutcome.Failed("zones need the zoned layout", null, "", "")
        val setup = ZoneSetup(engine, layout, request.offered, matcher, request.pageAspect)
        templateId = setup.template.id
        templateScore = setup.match.score
        traceSetup(setup)
        return try {
            InterpretationOutcome.Answered(read(setup, layout, request.direction, request.forcedFamily), transcriptText(), ZonePrompt.scoringSystem(setup.template), "")
        } catch (e: Abort) {
            session.close()
            InterpretationOutcome.Failed(e.reason, transcriptText().take(FAILED_RAW_CHARS), ZonePrompt.scoringSystem(setup.template), "")
        }
    }

    private suspend fun read(setup: ZoneSetup, layout: LetterLayout, direction: DocDirection, forcedFamily: String?): RawInterpretation {
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
            if (!tryOpen(head, closing, "header")) throw Abort("the model could not read the letter")
            inPrefix = emptySet()
            prescored.clear()
            // Questions that bring the same zone block and ask about the same values are scored ahead as a grid (see [prescore]).
            prescore(
                setup,
                headerParties.mapNotNull { partyAsk(setup, it.first, widen = false) } + headerSlots.mapNotNull { slotAsk(setup, it, widen = false) },
            )
            // A party whose header zones offered no name is asked again with the body, on the zones the registry names
            // as its fallback (the sender's name is sometimes only in the footer).
            headerParties.forEach { if (!party(setup, s, it.first, it.second, widen = false)) deferredParties += it }
            headerSlots.forEach { if (!slot(setup, s, it, widen = false)) deferred += it }
        }

        // ── body session: body, footer and the header's summary are the prefix ──
        val zonesInPrefix = bodyZones(setup)
        val summary = ZonePrompt.summary(s.established)
        val budget = openBody(setup, zonesInPrefix, summary) ?: throw Abort("the model could not read the letter")
        unread = zoned.coverage(zonesInPrefix, budget).takeIf { it.droppedLines > 0 }?.let { UnreadText(it.droppedLines, it.firstCutPage) }
        unread?.let { traceLines += "unread lines=${it.lines} firstCutPage=${it.firstCutPage} budgetChars=$budget" }

        val classification = classify(direction, forcedFamily)
        val family = classification.family
        val topics = if (topicsInFirstStage) classification.topics else emptyList()
        ownSlots = schema.ownSlots(family, topics)
        // A document the model took for a few lines of a message or a reminder has no addressee: what the header read as one is dropped and
        // none is asked for (the sender still is). Decided by the type the model chose, never by the words.
        // The general "Document" (no specific type detected) asks no letter question at all: the parties and slots the header session already
        // scored (it runs before the type is known) are dropped too.
        if (!family.asksFields) {
            s.parties.clear()
            s.slots.clear()
            s.senderId = null
            scored.clear()
        } else if (!family.hasAddressee) {
            s.parties.removeAll { it.role != PartyRole.SENDER.name }
            scored.removeAll { it.role != null && it.role != PartyRole.SENDER }
        }
        val bodyParties = (partyNames.filter { !isHeader(it.first) } + deferredParties)
            .filter { family.asksFields && (family.hasAddressee || it.second == PartyRole.SENDER) }
        val bodySlots = if (!family.asksFields) emptyList() else {
            (Slots.CORE + schema.slotsFor(family, topics)).distinct().filter { !isHeader(QuestionNames.slot(it.json), it) } + deferred
        }
        prescored.clear()
        prescore(setup, bodyParties.mapNotNull { partyAsk(setup, it.first, widen = true) } + bodySlots.mapNotNull { slotAsk(setup, it, widen = true) })
        bodyParties.forEach { (name, role) -> party(setup, s, name, role, widen = true) }
        bodySlots.forEach { slot(setup, s, it, widen = true) }
        val decoding = System.nanoTime()
        redecide(setup, s)
        timing("decode ms=${(System.nanoTime() - decoding) / NANOS_PER_MS} (includes the kind and household scoring of a changed answer)")
        traceFinal(setup, s)

        // The structured address of the addressee and of the sender, once the parties are settled (a letter with a recipient block only).
        val addresses = if (family.hasRecipientBlock) readAddresses(setup, layout, s) else null

        // Everything a person needs to see is decided: the family, the parties, the slots and the addresses. The extras, the language
        // and the free text are the second stage ([enrich]); the body session stays open for it, and so does what it was told of the header.
        timing(
            String.format(
                Locale.ROOT, "scoring total batches=%d scores=%d ms=%d msPerScore=%.0f", scoreBatches, scoreCount, scoreMs,
                if (scoreCount > 0) scoreMs.toDouble() / scoreCount else 0.0,
            ),
        )
        letter = LetterSession(setup, zonesInPrefix, budget)
        return RawInterpretation(
            type = family.id, typeConfidence = classification.familyConfidence, language = null,
            parties = s.parties.take(StructuredGrammar.MAX_PARTIES), slots = s.slots, established = summary,
            topics = topics, layoutTemplate = setup.template.id,
            addresses = addresses?.addresses.orEmpty(), senderAddressAlternatives = addresses?.senderAlternatives.orEmpty(),
        )
    }

    /** What the second stage needs of the first: the zoned letter, the zones the body session's prefix holds and the budget it was cut to. */
    private class LetterSession(val setup: ZoneSetup, val zonesInPrefix: List<LetterZone>, val budget: Int)

    /** The zones whose text the body session holds as its prefix: body, subject, payment and the footer. */
    private fun bodyZones(setup: ZoneSetup): List<LetterZone> = (setup.bodyZones + setup.zoned.mapped(LetterZone.FOOTER)).distinct()

    /**
     * Opens the body session: the scoring instruction, what the header established ([summary]) and the body and footer zones, each with its
     * hint, as the prefix (halving the text until it fits).
     *
     * @return the character budget the letter was rendered with, or null when the model could not read it.
     */
    private suspend fun openBody(setup: ZoneSetup, zonesInPrefix: List<LetterZone>, summary: String): Int? {
        var budget = ZoneSetup.bodyBudgetChars(contextTokens)
        for (attempt in 0 until MAX_OPEN_ATTEMPTS) {
            val (head, closing) = setup.frame(ZonePrompt.scoringSystem(setup.template), ZonePrompt.bodyUser(summary, zonedText(setup, zonesInPrefix, budget)))
            if (tryOpen(head, closing, "body#${attempt + 1}")) {
                inPrefix = zonesInPrefix.toSet()
                return budget
            }
            budget /= 2
        }
        return null
    }

    private fun zonedText(setup: ZoneSetup, zones: List<LetterZone>, budget: Int): String {
        val hints = zones.filter { setup.zoned.hasText(it) }.joinToString("\n") { "ZONE ${it.tag}. HINT: ${setup.plan.hint(it)}" }
        val text = setup.zoned.render(zones, budget)
        // How much text there is, as context only (see [LetterExtent]): the family is asked after this, and a few lines are not a letter.
        return LetterExtent.describe(text) + "\n" + hints + "\n" + text
    }

    /** Reopens the session for writing: the same letter, under [ZonePrompt.writingSystem] instead of the scoring instruction. */
    private suspend fun switchToWriting(setup: ZoneSetup, bodyUser: String): Boolean {
        val (head, closing) = setup.frame(ZonePrompt.writingSystem(setup.template), bodyUser)
        return tryOpen(head, closing, "writing")
    }

    override val staged: Boolean = true

    /**
     * The second stage: the extras (scored in the body session, which an engine that still holds the same prefix does not decode again),
     * then, in a writing session (a small model obeys "answer Yes or No" over any question, so what is written is asked under an
     * instruction that only says to write what is asked), the language, the extras' names and the free text.
     */
    override suspend fun enrich(request: EnrichmentRequest): EnrichmentOutcome {
        val layout = request.layout ?: return EnrichmentOutcome.Failed("zones need the zoned layout")
        failures = 0
        try {
            val open = letter ?: run {
                val setup = ZoneSetup(engine, layout, request.offered, matcher, request.pageAspect)
                val zones = bodyZones(setup)
                inPrefix = emptySet()
                val budget = openBody(setup, zones, request.established) ?: return EnrichmentOutcome.Failed("the model could not read the letter")
                LetterSession(setup, zones, budget).also { letter = it }
            }
            // The family's hint says what matters in this kind of document: it steers which facts are kept as the key information
            // (the extras); the model still decides, code only verifies.
            val hint = (request.documentTypeId?.let { schema.family(it) } ?: schema.abstain)?.hint
            val picks = pickExtras(open.setup, request.takenIds, hint, request.slots)
            val picked = picks.extras
            // A profile that keeps the topics out of the first stage scores them here, still in the body session.
            val lateTopics = if (topicsInFirstStage || request.topics.isNotEmpty()) null else classifier().topics(tail)
            // What the reader has to do is scored too, in the same body session: a kind is chosen from the catalogue, nothing is written.
            val actions = readActions(open.setup, request)
            // The letter as plain text: no zone hints and no summary of the header, which a small model copies instead of the letter.
            val writing = switchToWriting(open.setup, ZonePrompt.bodyUser("", open.setup.zoned.render(open.zonesInPrefix, open.budget)))
            if (!writing) {
                return EnrichmentOutcome.Done(
                    Enrichment(
                        language = null, extras = emptyList(), text = null, textError = "the model could not read the letter again", topics = lateTopics,
                        actions = actions, keySlots = picks.keySlots,
                    ),
                )
            }
            val language = ask(QuestionnairePrompt.language())?.let { AnswerReader.language(it) }
            val extras = nameExtras(open.setup, picked)
            val text = ZoneFreeText.write(includeSummary = false) { q -> ask(q) }
            val written = (text as? TextOutcome.Written)?.text
            // The summary rests on verified facts only: the subject line counts as one when it is printed in the letter.
            val subject = written?.subject?.takeIf { QuoteVerifier.verifyCopiedLine(it, request.ocrText) != null }
            val facts = SummaryFacts.of(request.documentTypeId ?: schema.abstain?.id.orEmpty(), request.facts, subject)
            val summary = SummaryWriter(FramedSession()).write(facts, request.ocrText, language)
            return EnrichmentOutcome.Done(
                Enrichment(
                    language = language, extras = extras, text = written,
                    textError = (text as? TextOutcome.Failed)?.reason, summary = summary, topics = lateTopics, actions = actions,
                    keySlots = picks.keySlots,
                ),
            )
        } catch (e: Abort) {
            return EnrichmentOutcome.Failed(e.reason)
        } finally {
            letter = null
            session.close()
        }
    }

    /**
     * What the reader has to do, chosen by score from the catalogue of action kinds ([ActionKindReader]) with the reading's own scorer, in
     * the open body session. Null when the kinds could not be scored, so the stored actions stay.
     */
    private suspend fun readActions(setup: ZoneSetup, request: EnrichmentRequest): List<ActionItem>? {
        // What the reading decided the document is goes into the gate question as context: the model's own conclusion, nothing written here.
        val family = request.documentTypeId?.let(schema::family)
        val reading = ActionKindReader({ name, questions -> scoreBatch(name, "", questions) }, profile.actions, schema = schema, trace = { traceLines += it })
            .read(request.slots, senderKnown = !request.facts[SummaryFacts.SENDER].isNullOrBlank(), family = family)
        // A recording run also scores the gate questions over an empty letter, for every family: the content-free baseline the gate is
        // measured from. It runs last in the body session, which the writing session replaces right after.
        if (profile.actions.scoreEveryBinding) scoreBaseline(setup)
        return reading?.items
    }

    /** The gate questions over an empty letter, one per family and the plain one, recorded as `score:action:baseline` (for fitting only). */
    private suspend fun scoreBaseline(setup: ZoneSetup) {
        val (head, closing) = setup.frame(ZonePrompt.scoringSystem(setup.template), ZonePrompt.bodyUser("", "(the letter has no text)"))
        if (!tryOpen(head, closing, "baseline")) return
        val questions = (listOf<DocFamily?>(null) + schema.families.filter { it.scored }).flatMap { ActionKindReader.gateQuestions(it) }.distinct()
        scoreBatch(ActionKindReader.BASELINE_BATCH, "", questions)
    }

    private fun traceSetup(setup: ZoneSetup) {
        val m = setup.match
        val top = m.scores.entries.sortedByDescending { it.value }.take(3).joinToString(" ") { it.key + String.format(Locale.ROOT, "=%.2f", it.value) }
        traceLines += String.format(Locale.ROOT, "template=%s score=%.2f fallback=%s top=[%s]", setup.template.id, m.score, m.isFallback, top)
        val zoned = setup.zoned
        val zones = LetterZone.entries.filter { zoned.hasText(it) }.joinToString(" ") { z ->
            "${z.tag}(lines=${zoned.lines(z).size},cands=${zoned.candidatesIn(listOf(z)).rows.size})"
        }
        traceLines += "zones $zones"
    }

    /** One scored question: how many candidates on which zones, the pick with its zones, kind and score, and the runner-up's score. */
    private fun traceAsk(setup: ZoneSetup, name: String, zones: List<LetterZone>, cands: List<Candidate>, scores: List<Double>, best: Int?) {
        val asked = zones.joinToString("+") { it.tag }
        if (best == null) {
            traceLines += "ask $name zones=$asked cands=${cands.size} pick=none"
            return
        }
        val second = scores.indices.filter { it != best }.maxOfOrNull { scores[it] }
        val c = cands[best]
        traceLines += String.format(
            Locale.ROOT, "ask %s zones=%s cands=%d pick=%s kind=%s in=%s best=%+.2f second=%s", name, asked, cands.size, c.id, c.kind.name,
            setup.zoned.zonesOfCandidate(c.id).joinToString("+") { it.tag }, scores[best], second?.let { String.format(Locale.ROOT, "%+.2f", it) } ?: "-",
        )
    }

    private fun traceFinal(setup: ZoneSetup, s: State) {
        fun where(id: String) = setup.zoned.zonesOfCandidate(id).joinToString("+") { it.tag }
        s.parties.forEach { traceLines += "final party ${it.role} id=${it.id} kind=${it.kind} in=${where(it.id)} conf=${it.confidence}" }
        s.slots.forEach { (k, v) -> traceLines += "final slot $k id=${v.id ?: v.ids.joinToString(",")} in=${v.id?.let(::where).orEmpty()} conf=${v.confidence}" }
    }

    /** A value picked as an extra: the candidate and the score that picked it. */
    private class Picked(val candidate: Candidate, val score: Double)

    /**
     * Decides the extras: values of the extras zones that no slot or party took and that the model scores as an important fact of
     * the letter ([ScoringDescriptions.EXTRA]), the best [StructuredGrammar.MAX_EXTRAS] above the `extras` threshold. A score, as
     * for every other value. Names are never offered: the parties were scored already, and the names the extractor finds that are
     * not a party are mostly labels and fragments of lines ("Fällig am", "Betrag €"). A failed scoring leaves no extras.
     */
    private suspend fun pickExtras(setup: ZoneSetup, taken: Set<String>, hint: String?, slots: List<TicketSlot>): Picks {
        val zoned = setup.zoned
        val zones = setup.plan.zones(QuestionNames.EXTRAS_SCORED).filter { zoned.hasText(it) }
        // Never key information: a value of a table's row (a position, a price: the table is read as a whole, its totals are slots), and a
        // value found by shape alone with nothing printed beside it that names it (a signature fragment, a serial): the model's lean Yes
        // cannot give such a value a meaning, however it is worded.
        val cands = if (zones.isEmpty()) emptyList() else zoned.candidatesIn(zones).rows.map { it.candidate }.filter {
            it.id !in taken && it.kind != CandidateKind.NAME && !zoned.isTableCell(it) && !(it.attrs["shape"] != null && zoned.printedLabel(it) == null)
        }
        // The stored slot values ride in the same batch (one decode of the shared block): does the reader need this one, given the hint.
        val asked = slots.take(ScoringDescriptions.MAX_KEY_SLOT_SCORES)
        if (cands.isEmpty() && asked.isEmpty()) return Picks(emptyList(), null)
        val block = if (cands.isEmpty()) "" else block(setup, zones)
        val scores = scoreBatch(
            ScoringDescriptions.EXTRAS_ASK, block,
            cands.map { ZonePrompt.scoringQuestion(it.raw.replace('\n', ' '), zoned.context(it), ScoringDescriptions.extra(hint)) } +
                asked.map { ZonePrompt.keySlotQuestion(it.label, it.value, hint) },
        ) ?: return Picks(emptyList(), null)
        val threshold = profile.threshold(ScoringDescriptions.EXTRAS_ASK)
        val slotThreshold = profile.threshold(ScoringDescriptions.KEY_SLOTS_ASK)
        // The key information is ONE short list: the extras and the stored slot values that scored Yes compete by score, and only the
        // best MAX_KEY_INFO of them are kept, so the section stays short whatever a small model leaned. The rest stay under "All details".
        val pool = cands.indices.filter { scores[it] > threshold }.map { Choice(extra = it, slot = null, score = scores[it]) } +
            asked.indices.filter { scores[cands.size + it] > slotThreshold }.map { Choice(extra = null, slot = it, score = scores[cands.size + it]) }
        val kept = pool.sortedByDescending { it.score }.take(ScoringDescriptions.MAX_KEY_INFO)
        val extras = kept.filter { it.extra != null }.map { Picked(cands[it.extra!!], it.score) }
        val keySlots = kept.filter { it.slot != null }.map { KeySlot(asked[it.slot!!].key, it.score.toFloat()) }
        return Picks(extras, if (asked.isEmpty()) null else keySlots)
    }

    /** One candidate for the key information: an extra (index into the offered candidates) or a stored slot (index into the asked slots), and its score. */
    private class Choice(val extra: Int?, val slot: Int?, val score: Double)

    /** What the extras batch decided: the extras picked, and the stored slots picked as key information (null when none were scored). */
    private class Picks(val extras: List<Picked>, val keySlots: List<KeySlot>?)

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

    /** The zones a question is about, each with its hint and (unless the session's prefix holds it) its text, and the glimpse when asked for. No candidates: nothing is offered as an option. */
    private fun block(setup: ZoneSetup, zones: List<LetterZone>): String {
        val fresh = zones.filter { it !in inPrefix }
        val glimpse = if (neighbourContext && fresh.isNotEmpty()) setup.zoned.glimpse(fresh).takeIf { it.before != null || it.after != null } else null
        return ZonePrompt.zoneBlock(zones, setup.plan::hint, setup.zoned::zoneText, inPrefix, candidates = null, glimpse = glimpse)
    }

    // ── the family and the topics ──

    /** The one classifier of this reading: its batch is recorded and counted like every other scored batch (`score:family`). */
    private fun classifier(): FamilyClassifier = FamilyClassifier(session, profile, schema) { record ->
        records += record
        val n = record.question.split(BATCH_SEPARATOR).size
        scoreBatches++
        scoreCount += n
        scoreMs += record.ms
        timing(String.format(Locale.ROOT, "score %s n=%d ms=%d msPerScore=%.0f%s", record.name.removePrefix("score:"), n, record.ms, record.ms.toDouble() / n, if (record.answer == null) " FAILED" else ""))
    }

    /**
     * What the letter is and what it is about: the family among those a document of [direction] can be ([ExtractionSchema.familiesFor]; a
     * received letter is never offered "a letter the reader sent"), or [forcedFamily] when a person chose one, and the topics when they
     * are scored in this stage.
     */
    private suspend fun classify(direction: DocDirection, forcedFamily: String?): Classification {
        val forced = forcedFamily?.let(schema::family)
        val classifier = classifier()
        val result = (if (forced != null) classifier.classify(forced, tail, topicsInFirstStage) else classifier.classify(direction, tail, topicsInFirstStage))
            ?: throw Abort("the family could not be scored")
        val families = result.scores.filterKeys { it.startsWith("family:") }.entries.sortedByDescending { it.value }
        traceLines += "family offered=${families.size} forced=${forced != null} " +
            families.take(3).joinToString(" ") { it.key.removePrefix("family:") + String.format(Locale.ROOT, "=%+.2f", it.value) } +
            " -> ${result.family.id} topics=${result.topics.joinToString(",")}"
        return result
    }

    // ── the structured address ──

    /** The parties the reading settled, as the address reader takes them: names as printed, kind and relation, the model's own confidence. */
    private fun partiesOf(setup: ZoneSetup, raw: List<RawParty>): Parties = Parties(
        raw.mapNotNull { p ->
            val role = PartyRole.entries.firstOrNull { it.name == p.role } ?: return@mapNotNull null
            val c = setup.zoned.offered.get(p.id) ?: return@mapNotNull null
            val ai = ConfidenceCombiner.aiScore(p.confidence)
            val value = SlotValue(
                slot = null, candidateId = c.id, value = c.raw.trim(), normalized = c.normalized, page = c.page, bbox = c.bbox,
                evidence = c.evidence, origin = SlotOrigin.MODEL_CHOICE, aiConfidence = ai, confidence = ai, validation = c.validation,
                role = role.name,
            )
            Party(
                role, PartyKind.entries.firstOrNull { it.name == p.kind } ?: PartyKind.OTHER,
                PartyRelation.entries.firstOrNull { it.name == p.relation } ?: PartyRelation.NONE, value,
            )
        },
    )

    /**
     * Reads the addressee's and the sender's postal address once the parties are settled; its scored cells (a grid per block) go through
     * [AddressSession], so they are recorded and counted like the other scores.
     */
    private suspend fun readAddresses(setup: ZoneSetup, layout: LetterLayout, s: State): AddressReading {
        val started = System.nanoTime()
        val reading = addressReader.read(layout, partiesOf(setup, s.parties), setup.match, AddressSession(), tail)
        timing(
            String.format(
                Locale.ROOT, "addresses recipientCells=%d senderCells=%d retried=%s ms=%d", reading.recipientCells, reading.senderCells, reading.retried,
                (System.nanoTime() - started) / NANOS_PER_MS,
            ),
        )
        return reading
    }

    /** The letter's session as the address labeler sees it: every grid it scores is recorded (`score:addr`, one record per statement) and counted. */
    private inner class AddressSession : PromptSession by session {
        override suspend fun scoreGrid(shared: String, heads: List<String>, asks: List<String>, yes: String, no: String): PamResult<List<List<Double>>> {
            val started = System.nanoTime()
            val result = session.scoreGrid(shared, heads, asks, yes, no)
            val ms = (System.nanoTime() - started) / NANOS_PER_MS
            val grid = (result as? PamResult.Success)?.data?.takeIf { it.size == heads.size && it.all { row -> row.size == asks.size } }
            asks.forEachIndexed { j, ask ->
                records += AskRecord(
                    name = "score:addr",
                    question = heads.joinToString(BATCH_SEPARATOR) { shared.removePrefix("\n\n") + it + ask.removeSuffix(tail) },
                    answer = grid?.joinToString(",") { row -> row[j].toString() }, ms = ms / asks.size,
                )
            }
            scoreBatches += asks.size
            scoreCount += heads.size * asks.size
            scoreMs += ms
            timing(String.format(Locale.ROOT, "score addr grid heads=%d asks=%d ms=%d%s", heads.size, asks.size, ms, if (grid == null) " FAILED" else ""))
            return result
        }
    }

    /**
     * The letter's session as the summary's writer sees it: its questions are framed like every other ask (the
     * turn's opening and closing) and recorded under [name].
     */
    private inner class FramedSession(private val name: String = "text:summary") : PromptSession by session {
        override suspend fun ask(question: String, grammar: String, maxTokens: Int): PamResult<String> {
            val started = System.nanoTime()
            val result = session.ask("\n\n" + question + tail, grammar, maxTokens)
            val ms = (System.nanoTime() - started) / NANOS_PER_MS
            val answer = (result as? PamResult.Success)?.data?.trim()
            records += AskRecord(name, question, answer, ms)
            timing("ask $name ms=$ms answerChars=${answer?.length ?: 0}${if (answer == null) " FAILED" else ""}")
            return result
        }
    }

    // ── planned questions, scored as a grid where they share ──

    /** One question of the reading, planned before it is asked: the zones it is about, the candidates it scores and what it asks of each. */
    private class Ask(val name: String, val zones: List<LetterZone>, val cands: List<Candidate>, val what: String, val labelled: Boolean = false)

    /** The slots this document is expected to have beyond the core (its family's own and its topics'): the ones that are not [ScoringProfile.optionalUnlessOwn]-optional for it. Set once the family is known. */
    private var ownSlots: Set<SlotKey> = emptySet()

    /** Whether [slot] may be none for this document (see [ScoringProfile.isOptional]). */
    private fun optional(slot: SlotKey): Boolean = profile.isOptional(QuestionNames.slot(slot.json), slot in ownSlots)

    /** The threshold a slot question abstains under: its own, or the optional level for a number this document may not have. */
    private fun slotThreshold(name: String, slot: SlotKey?): Double =
        if (slot == null) profile.threshold(name) else profile.slotThreshold(name, slot in ownSlots)

    /**
     * A reference found by shape alone in the small print at the foot of a page (a register or tax digit run): input for the extras, never offered
     * to a slot. Decided by where it sits and how it was found, not by any word.
     */
    private fun isFooterShape(zoned: ZonedLetter, c: Candidate): Boolean {
        if (c.attrs["shape"] == null) return false
        val zones = zoned.zonesOfCandidate(c.id)
        return zones.isNotEmpty() && zones.all { it == zoned.mapped(LetterZone.FOOTER) }
    }

    /** Scores computed ahead by [prescore], by question name; a question takes its scores from here (once) before it would score on its own. */
    private val prescored = HashMap<String, List<Double>>()

    /** The party question [name]: every name of its zones (or of its fallback zones when [widen] and none is there), or null when nothing is offered. */
    private fun partyAsk(setup: ZoneSetup, name: String, widen: Boolean): Ask? {
        val zoned = setup.zoned
        var zones = setup.plan.zones(name).filter { zoned.hasText(it) }
        // Every name of the zone is scored, whatever was decided before: what is scored then does not depend on the
        // thresholds, which is what lets a recording be re-decided offline. The one exclusion code makes (the sender is
        // not also the addressee, the routing person or the mailbox) is applied to the choice.
        // A name that is a cell of a table (a column header such as "Einzelpreis € Gesamt €", a position) is never a party or a person:
        // by where it is printed, not by any word.
        fun names(z: List<LetterZone>) = zoned.candidatesIn(z).rows.map { it.candidate }.filter { it.kind == CandidateKind.NAME }
        var cands = names(zones)
        if (cands.isEmpty() && widen) {
            zones = SlotPlacements.partyFallback(name).map { zoned.mapped(it) }.distinct().filter { zoned.hasText(it) }
            cands = names(zones)
        }
        // Where the names are looked for is decided as before; the cells of a table are left out of what is scored.
        cands = cands.filter { !zoned.isTableCell(it) }
        if (zones.isEmpty() || cands.isEmpty()) return null
        return Ask(name, zones, cands, ScoringDescriptions.ofRole(name))
    }

    /** The slot question of [slot]: every candidate of its kind in the zones the template places it on (anywhere when [widen] and none is there). */
    private fun slotAsk(setup: ZoneSetup, slot: SlotKey, widen: Boolean): Ask? {
        if (slot.kind == SlotKind.ACTION) return null
        val zoned = setup.zoned
        val name = QuestionNames.slot(slot.json)
        val zones = setup.plan.zones(name, slot).filter { zoned.hasText(it) }
        val isReference = slot.kind == SlotKind.REFERENCE || slot.kind == SlotKind.REFERENCE_LIST
        // A number the document may not have is taken only from a value that prints a label of its own: a digit-and-letter run found by
        // shape alone (a signature fragment, a serial) has nothing printed beside it that says what it is, so a small model's lean Yes on
        // an optional slot is never enough to give it a name such as "Case number".
        fun offered(rows: List<Candidate>) = rows.filter {
            it.kind in slot.kind.candidates && !(isReference && (isFooterShape(zoned, it) || (optional(slot) && it.attrs["shape"] != null)))
        }
        var cands = offered(zoned.candidatesIn(zones).rows.map { it.candidate })
        var asked = zones
        // A number the document may not have is never widened to the whole letter: that its zones hold none is an answer.
        if (cands.isEmpty() && widen && !optional(slot)) {
            cands = offered(zoned.offered.rows.map { it.candidate })
            asked = cands.flatMap { zoned.zonesOfCandidate(it.id) }.distinct().filter { zoned.hasText(it) }
        }
        if (cands.isEmpty()) return null
        return Ask(name, asked, cands, ScoringDescriptions.ofSlot(slot), labelled = isReference && optional(slot))
    }

    /** The scores of [ask]'s candidates: the ones [prescore] computed, else scored now as a batch under [block]. */
    private suspend fun scored(setup: ZoneSetup, ask: Ask, block: String): List<Double>? =
        prescored.remove(ask.name) ?: scoreBatch(
            ask.name, block,
            ask.cands.map { ZonePrompt.scoringQuestion(it.raw.replace('\n', ' '), setup.zoned.context(it), ask.what, if (ask.labelled) setup.zoned.printedLabel(it) else null) },
        )

    /**
     * Scores ahead the questions that share a zone block and the same candidates (the reference slots of a letter, its date slots...) as
     * a grid: the block is decoded once, each candidate's head once, and only each statement is decoded per candidate. The text of every
     * cell is what a batch of its own would have read (block, head, statement), so the scores and the recording are the same; only the
     * decoding is shared (`PromptSession.scoreGrid`). A grid the engine failed is left to the questions' own batches.
     */
    private suspend fun prescore(setup: ZoneSetup, asks: List<Ask>) {
        if (!profile.prefixTree) return
        val zoned = setup.zoned
        // The questions that show the printed label do not share their head with those that do not.
        for ((key, group) in asks.groupBy { Triple(block(setup, it.zones), it.cands.map { c -> c.id }, it.labelled) }) {
            if (group.size < 2) continue
            val block = key.first
            val cands = group.first().cands
            val heads = cands.map { ZonePrompt.scoringHead(it.raw.replace('\n', ' '), zoned.context(it), if (key.third) zoned.printedLabel(it) else null) }
            val statements = group.map { ZonePrompt.scoringAsk(it.what) }
            val started = System.nanoTime()
            val result = session.scoreGrid("\n\n" + block, heads, statements.map { it + tail }, YES, NO)
            val ms = (System.nanoTime() - started) / NANOS_PER_MS
            val grid = (result as? PamResult.Success)?.data?.takeIf { it.size == heads.size && it.all { row -> row.size == group.size } }
            if (grid == null) {
                timing("score grid ${group.joinToString("+") { it.name }} FAILED ms=$ms")
                if (++failures >= MAX_FAILURES) throw Abort("the engine failed $MAX_FAILURES scorings in a row")
                continue
            }
            failures = 0
            group.forEachIndexed { j, ask ->
                val scores = grid.map { it[j] }
                prescored[ask.name] = scores
                records += AskRecord(
                    name = "score:${ask.name}", question = heads.joinToString(BATCH_SEPARATOR) { block + it + statements[j] },
                    answer = scores.joinToString(","), ms = ms / group.size,
                )
            }
            scoreBatches += group.size
            scoreCount += heads.size * group.size
            scoreMs += ms
            timing(
                String.format(
                    Locale.ROOT, "score grid %s heads=%d asks=%d ms=%d msPerScore=%.0f", group.joinToString("+") { it.name }, heads.size, group.size, ms,
                    ms.toDouble() / (heads.size * group.size),
                ),
            )
        }
    }

    // ── parties ──

    /**
     * Scores the names of the zones [name] is asked on; with [widen] (body session only) and no name there, the zones the
     * registry gives as the party's fallback ([SlotPlacements.partyFallback]).
     *
     * @return whether any name was scored (false: nothing was offered, so the caller may ask again where the fallback is).
     */
    private suspend fun party(setup: ZoneSetup, s: State, name: String, role: PartyRole, widen: Boolean): Boolean {
        val ask = partyAsk(setup, name, widen) ?: return false
        val zones = ask.zones
        val cands = ask.cands
        val block = block(setup, zones)
        val scores = scored(setup, ask, block) ?: return true
        // "None of these" is an answer: a name is taken only when it also beats a made-up name asked the same way over the same zones, so the
        // threshold of the question is raised to that level (the decoder, which re-decides from the scores, then abstains the same way).
        val threshold = maxOf(profile.threshold(name), baselineFloor(ask, block) ?: Double.NEGATIVE_INFINITY)
        collect(name, cands, scores, role = role, slot = null, block = block, threshold = threshold)
        val allowed = scores.indices.filter { role == PartyRole.SENDER || cands[it].id != s.senderId }
        val ranked = allowed.sortedByDescending { scores[it] }
        traceAsk(setup, name, zones, cands, scores, ranked.firstOrNull())
        val best = ranked.firstOrNull() ?: return true
        if (scores[best] <= threshold) return true
        val (confidence, note) = confidenceOf(scores[best], ranked.getOrNull(1)?.let { scores[it] }, ranked.size)
        addParty(setup, s, name, role, cands[best], block, confidence, note, ranked.drop(1).take(MAX_ALTERNATIVES).map { RawAlternative(cands[it].id, scores[it]) })
        return true
    }

    /**
     * The score a name of [ask] must be above to be taken: the content-free baseline (the same question about a made-up name,
     * [ScoringDescriptions.PARTY_BASELINE_NAME], over the same zone block) plus the profile's margin. Null when the profile sets no margin
     * for this question, and when the baseline could not be scored (nothing is then known against the name, so it is kept as before).
     */
    private suspend fun baselineFloor(ask: Ask, block: String): Double? {
        val margin = profile.partyBaselineMargin(ask.name) ?: return null
        val baseline = scoreBatch(
            "baseline:${ask.name}", block, listOf(ZonePrompt.scoringQuestion(ScoringDescriptions.PARTY_BASELINE_NAME, null, ask.what)),
        )?.firstOrNull() ?: return null
        traceLines += String.format(Locale.ROOT, "%s baseline %+.2f margin %.2f -> a name needs more than %+.2f", ask.name, baseline, margin, baseline + margin)
        return baseline + margin
    }

    /** The party [c] as [role]: its kind and (for an addressee) household relation scored, recorded as the sender or addressee established so far. */
    private suspend fun addParty(
        setup: ZoneSetup, s: State, name: String, role: PartyRole, c: Candidate, block: String, confidence: String, note: String,
        alternatives: List<RawAlternative> = emptyList(),
    ) {
        val zoned = setup.zoned
        val ctx = zoned.context(c)
        val printed = c.raw.replace('\n', ' ')

        // The three kinds are asked about the same value: its zone block and its head are the shared level, each statement is its own.
        val kinds = scoreBatch("kind:$name", block + ZonePrompt.scoringHead(printed, ctx), ScoringDescriptions.KINDS.map { (_, statement) -> ZonePrompt.scoringAsk(statement) })
        val kind = kinds?.let { ks -> ScoringDescriptions.KINDS[ks.indices.maxByOrNull { ks[it] } ?: 0].first } ?: "OTHER"
        var relation: String? = null
        if (role == PartyRole.ADDRESSEE) {
            val h = scoreBatch("household", block, listOf(ZonePrompt.scoringQuestion(printed, ctx, ScoringDescriptions.HOUSEHOLD)))
            relation = if (h != null && h.first() > 0.0) "HOUSEHOLD" else "NONE"
        }
        if (role == PartyRole.SENDER) s.senderId = c.id
        if (role == PartyRole.SENDER || role == PartyRole.ADDRESSEE) {
            s.established += (if (role == PartyRole.SENDER) "sender" else "addressee") to "${c.id} «$printed»"
        }
        s.parties += RawParty(
            role = role.name, id = c.id, kind = kind, relation = relation,
            confidence = confidence, name = null, scoreNote = note, alternatives = alternatives,
        )
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
        val ask = slotAsk(setup, slot, widen) ?: return false
        val name = ask.name
        val asked = ask.zones
        val cands = ask.cands
        val block = block(setup, asked)
        val scores = scored(setup, ask, block) ?: return true
        if (slot.kind != SlotKind.REFERENCE_LIST) collect(name, cands, scores, role = null, slot = slot, block = block)
        val threshold = slotThreshold(name, slot)
        val order = scores.indices.sortedByDescending { scores[it] }
        val best = order.first()
        traceAsk(setup, name, asked, cands, scores, best)
        if (scores[best] <= threshold) return true
        val (confidence, note) = confidenceOf(scores[best], order.getOrNull(1)?.let { scores[it] }, order.size)
        s.slots[slot.json] = if (slot.kind == SlotKind.REFERENCE_LIST) {
            RawSlot(
                ids = order.filter { scores[it] > threshold }.take(StructuredGrammar.MAX_REF_IDS).map { cands[it].id },
                confidence = confidence, scoreNote = note,
            )
        } else {
            rawSlot(slot, cands[best].id, confidence, note, order.drop(1).take(MAX_ALTERNATIVES).map { RawAlternative(cands[it].id, scores[it]) })
        }
        return true
    }

    /** The answer of a single-valued [slot] won by [id]: the one place that maps a slot's kind to the role the value gets. */
    private fun rawSlot(slot: SlotKey, id: String, confidence: String, note: String, alternatives: List<RawAlternative> = emptyList()): RawSlot = when (slot.kind) {
        SlotKind.AMOUNT, SlotKind.DATE, SlotKind.DEADLINE ->
            RawSlot(id = id, role = roleOf(slot), confidence = confidence, scoreNote = note, alternatives = alternatives)
        else -> RawSlot(id = id, confidence = confidence, scoreNote = note, alternatives = alternatives)
    }

    /** The runner-ups of a decided question: its other candidates by score, best first, never the one [chosen] nor the sender's party where [excluded]. */
    private fun runnersUp(q: ScoredQuestion, chosen: Int, excluded: String?): List<RawAlternative> =
        q.candidates.indices.filter { it != chosen && q.candidates[it].id != excluded }
            .sortedByDescending { q.candidates[it].score }.take(MAX_ALTERNATIVES).map { RawAlternative(q.candidates[it].id, q.candidates[it].score) }

    /** The role a value has by winning [slot]: the slot's own expected role, in the schema's order. */
    private fun roleOf(slot: SlotKey): String {
        val order = if (slot.kind == SlotKind.AMOUNT) Roles.AMOUNT else Roles.DATE
        return order.firstOrNull { it in slot.expects } ?: "OTHER"
    }

    // ── decoding ──

    /** A scored question with what is needed to write its answer again: the slot or the party role, the zone block and the candidates asked. */
    private class Scored(val question: ScoredQuestion, val slot: SlotKey?, val role: PartyRole?, val block: String, val cands: List<Candidate>)

    private fun facts(c: Candidate) = CandidateFacts(c.id, c.kind, c.normalized, c.cents, c.currency)

    private fun collect(
        name: String, cands: List<Candidate>, scores: List<Double>, role: PartyRole?, slot: SlotKey?, block: String,
        threshold: Double = slotThreshold(name, slot),
    ) {
        val question = ScoredQuestion(
            name, cands.mapIndexed { i, c -> ScoredCandidate(facts(c), scores[i]) }, threshold,
            excludesWinnerOf = if (role != null && role != PartyRole.SENDER) QuestionNames.SENDER else null,
        )
        scored += Scored(question, slot, role, block, cands)
    }

    /**
     * Once every question is scored, the profile's [SlotDecoder] decides all of them together from the same scores. The answers
     * given as the questions were asked are kept wherever the decoder agrees (the per-slot argmax always does); where it
     * chose another candidate, or none, the answer is written again from the scores, with the confidence of the new choice
     * (its margin over the best other candidate of its question, so a second choice is LOW unless it was close).
     */
    private suspend fun redecide(setup: ZoneSetup, s: State) {
        if (scored.isEmpty()) return
        val decided = decoder.decode(scored.map { it.question }, setup.zoned.offered.rows.map { facts(it.candidate) })
        for (a in scored) {
            val q = a.question
            val now = decided[q.name]
            val slot = a.slot
            val before = if (slot != null) s.slots[slot.json]?.id else s.parties.firstOrNull { it.role == a.role?.name }?.id
            if (now == before) continue
            val i = q.candidates.indexOfFirst { it.id == now }
            val (confidence, note) = if (i < 0) "LOW" to "" else confidenceOf(
                q.candidates[i].score, q.candidates.filterIndexed { j, c -> j != i && c.id != q.excludesWinnerOf?.let { w -> decided[w] } }.maxOfOrNull { it.score }, q.candidates.size,
            )
            if (slot != null) {
                if (i < 0) {
                    s.slots.remove(slot.json)
                } else {
                    s.slots[slot.json] = rawSlot(slot, now!!, confidence, note, runnersUp(q, i, null))
                }
            } else if (a.role != null) {
                val at = s.parties.indexOfFirst { it.role == a.role.name }
                if (at >= 0) s.parties.removeAt(at)
                if (i >= 0) {
                    // What is scored again here was not scored when the question was asked; a failed scoring leaves the kind OTHER.
                    failures = 0
                    addParty(
                        setup, s, q.name, a.role, a.cands[i], a.block, confidence, note,
                        runnersUp(q, i, q.excludesWinnerOf?.let { w -> decided[w] }),
                    )
                    if (at >= 0) s.parties.add(at, s.parties.removeAt(s.parties.lastIndex))
                }
            }
        }
    }

    // ── engine ──

    private suspend fun tryOpen(head: String, closing: String, label: String): Boolean {
        val started = System.nanoTime()
        val opened = session.open(head)
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        prefixMs += ms
        if (opened !is PamResult.Success) {
            timing("open $label failed ms=$ms")
            return false
        }
        prefixTokens += opened.data
        timing("open $label prefixTokens=${opened.data} ms=$ms")
        tail = closing
        return true
    }

    /** A timing line of the reading trace (`t ...`): stage, counts and milliseconds, never a word of the letter. */
    private fun timing(line: String) {
        traceLines += "t $line"
    }

    /**
     * One score per question, or null when the engine failed the batch. Three failures in a row abort the reading.
     *
     * Every question of a batch starts with [shared] (the zone block, for kinds also the value): the engine decodes it once
     * (a level of the prefix tree) and each question pays only for its own [questions] text. The scores are those of the
     * text `shared + question` read whole, so where the text is split is a cost decision and never changes an answer; the
     * recording holds each question whole.
     */
    private suspend fun scoreBatch(name: String, shared: String, questions: List<String>): List<Double>? {
        if (questions.isEmpty()) return emptyList()
        val started = System.nanoTime()
        // A shared level is worth a checkpoint only when more than one question uses it, and only for a profile that accepts the split
        // decode (see [ScoringProfile.prefixTree]); otherwise every question is read whole, exactly as recorded.
        val tree = profile.prefixTree && shared.isNotEmpty() && questions.size > 1
        val result = if (tree) {
            session.score(questions.map { it + tail }, YES, NO, "\n\n" + shared)
        } else {
            session.score(questions.map { "\n\n" + shared + it + tail }, YES, NO)
        }
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        val scores = (result as? PamResult.Success)?.data?.takeIf { it.size == questions.size }
        records += AskRecord(
            name = "score:$name", question = questions.joinToString(BATCH_SEPARATOR) { shared + it }, answer = scores?.joinToString(","), ms = ms,
        )
        scoreBatches++
        scoreCount += questions.size
        scoreMs += ms
        timing(String.format(Locale.ROOT, "score %s n=%d ms=%d msPerScore=%.0f%s", name, questions.size, ms, ms.toDouble() / questions.size, if (scores == null) " FAILED" else ""))
        if (scores == null) {
            if (++failures >= MAX_FAILURES) throw Abort("the engine failed $MAX_FAILURES scorings in a row")
        } else {
            failures = 0
        }
        return scores
    }

    /** One generated answer, asked in the letter's session (no Yes or No instruction is in its prefix), or null when the engine failed it. */
    private suspend fun ask(question: Question): String? {
        val started = System.nanoTime()
        val result = session.ask("\n\n" + question.text + tail, question.grammar, question.maxTokens)
        val answer = (result as? PamResult.Success)?.data?.trim()
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        records += AskRecord(question.name, question.text, answer, ms)
        timing("ask ${question.name} ms=$ms answerChars=${answer?.length ?: 0}${if (answer == null) " FAILED" else ""}")
        return answer
    }

    /** This interpreter writes in its second stage ([enrich]); the pipeline never calls this for a [staged] interpreter. */
    override suspend fun writeText(request: TextRequest): TextOutcome = TextOutcome.Failed("the free text is written by the second stage")

    private fun transcriptText(): String = records.joinToString("\n") { "${it.name}: ${it.answer}" }

    companion object {
        const val YES = "Yes"
        const val NO = "No"

        /** Separates the questions of one scored batch in a recording. */
        const val BATCH_SEPARATOR = "\n@@\n"

        private const val NANOS_PER_MS = 1_000_000L

        /** The runner-up candidates kept per scored question (the Edit sheet's chips). */
        private const val MAX_ALTERNATIVES = 3
        private const val MAX_FAILURES = 3
        private const val MAX_OPEN_ATTEMPTS = 3
        private const val FAILED_RAW_CHARS = 300
        private const val OVERHEAD_CHARS = 700
    }
}
