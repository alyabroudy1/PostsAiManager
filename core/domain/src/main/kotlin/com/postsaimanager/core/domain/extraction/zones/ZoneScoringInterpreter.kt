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
import com.postsaimanager.core.domain.extraction.text.DocumentNameWriter
import com.postsaimanager.core.domain.extraction.text.KeyInfoWriter
import com.postsaimanager.core.domain.extraction.text.ReadFacts
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
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
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
import com.postsaimanager.core.domain.timeline.EventKindReader
import com.postsaimanager.core.domain.timeline.EventKinds
import com.postsaimanager.core.domain.timeline.EventTitleWriter
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.EventReading
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
 * What a document is comes LAST: the first stage reads the letter without knowing its type, and the second stage's first ask is
 * [FamilyClassifier], one batch ("Is this document <category>?" for every broad category, a made-up category as the content-free baseline,
 * "Does this document concern <topic>?" for every topic) preceded by what the first stage read ([ReadFacts]: the sender, the dates and amounts
 * with their meanings). The category is the argmax above the `family` threshold, the runner-up margin and the baseline, else the abstain
 * "Document". A category a person gave is context for every question ("The user says this document is a ..."), never a switch, and is not
 * decided again. [ExtractionSchema.slotsFor] names the slots a document of the direction can have (the core and every family's own), asked of
 * every document. After the parties are settled, a document with an addressee has its structured address read ([StructuredAddressReader]:
 * the line labels are scored too, never generated). Every scored question also keeps its runner-up candidates, the alternatives the
 * Edit sheet offers.
 *
 * The type is a label: every party and every slot is asked of every document (the general "Document" and a few lines of a message
 * included), and "none" is an answer each can give: a party, a reference, or a date or an amount beyond the core is taken only when it beats
 * a made-up name, reference, date or amount asked the same way by its margin ([ScoringProfile.baselineMargins]). What a kept date or amount
 * means is then scored once per value ([ValueMeaningReader]) and attached to the slot that holds it.
 *
 * The layout orders and never gates, as a CASCADE: the party questions, the date slots and the references score the candidates printed in the
 * zones they prefer first ([ZonePlan.tiers]), and the other zones' candidates only when none of those is taken (above the question's
 * threshold and its baseline plus margin). A name printed elsewhere on the page can therefore never outvote one in the preferred zones, a
 * sender printed outside them is still found, and a first tier that is taken costs fewer scored candidates. What a date means can veto the
 * slot that holds it ([MeaningVerdict]), and a party's best candidates are asked once whether they are a field label ([fieldLabels]). Every
 * question leaves one trace line (tier, counts, baseline, the winner's text; [traceAsk]).
 *
 * What needs writing is asked in the same open body session, each ask with its own small grammar: the letter's language
 * (BCP-47, one short ask), the naming of each extra ([extras]: which values no slot or party took is decided by score, the
 * words the letter prints next to it and an english key are written), the subject line and the suggested questions
 * ([ZoneFreeText]), the summary ([SummaryWriter]: it is given the verified facts and writes one or two sentences, a gate checks
 * them, a template stands in when it cannot) and the specific name of the document ([DocumentNameWriter], grounded by
 * [DocumentNameVerifier]). The title is composed from verified fields and that name. This runs after the reading, as the second stage. One combined generation for language and extras was measured on the device first and a 0.8B model
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
        userLine = userSentence(request.forcedFamily)
        prescored.clear()
        baselines.clear()
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
            QuestionNames.CONTACT to PartyRole.CONTACT, QuestionNames.SUBJECT_PERSON to PartyRole.SUBJECT_PERSON,
        )
        fun isHeader(name: String, slot: SlotKey? = null) = plan.isHeader(plan.zones(name, slot))

        // ── header session: the header zones are the prefix ──
        val headerParties = partyNames.filter { isHeader(it.first) }
        val headerSlots = Slots.CORE.filter { isHeader(QuestionNames.slot(it.json), it) }
        if (headerParties.isNotEmpty() || headerSlots.isNotEmpty()) {
            // The prefix is the instructions only: each question carries its own zone (and, with neighbour context,
            // a glimpse of the zones around it), so a zone is judged on its own text and hint.
            val (head, closing) = setup.frame(system, userContext() + ZonePrompt.HEADER_USER)
            if (!tryOpen(head, closing, "header")) throw Abort("the model could not read the letter")
            inPrefix = emptySet()
            prescored.clear()
            // Questions that bring the same zone block and ask about the same values are scored ahead as a grid (see [prescore]).
            val asks = headerParties.mapNotNull { partyAsk(setup, it.first) } + headerSlots.mapNotNull { slotAsk(setup, it) }
            prescore(setup, asks)
            scoreBaselines(setup, asks)
            // The layout only orders what a question scores: it is asked over every candidate of the page, the zones it prefers first.
            headerParties.forEach { party(setup, s, it.first, it.second) }
            headerSlots.forEach { slot(setup, s, it) }
        }

        // ── body session: body, footer and the header's summary are the prefix ──
        val zonesInPrefix = bodyZones(setup)
        val summary = ZonePrompt.summary(s.established)
        val budget = openBody(setup, zonesInPrefix, summary) ?: throw Abort("the model could not read the letter")
        unread = zoned.coverage(zonesInPrefix, budget).takeIf { it.droppedLines > 0 }?.let { UnreadText(it.droppedLines, it.firstCutPage) }
        unread?.let { traceLines += "unread lines=${it.lines} firstCutPage=${it.firstCutPage} budgetChars=$budget" }

        // The type is a label decided LAST (the second stage, from what was read): it removes no question and is not known here. Every party
        // and every slot a document of this direction can have is asked of every document, the general "Document" and a few lines of a
        // message included; "none" is an answer each question can give (the content-free baseline of its margin).
        val topics = if (topicsInFirstStage) (classifier().topics(tail) ?: throw Abort("the topics could not be scored")) else emptyList()
        val bodyParties = partyNames.filter { !isHeader(it.first) }
        val bodySlots = (schema.slotsFor(direction) + schema.topicSlots(topics)).distinct().filter { !isHeader(QuestionNames.slot(it.json), it) }
        prescored.clear()
        val bodyAsks = bodyParties.mapNotNull { partyAsk(setup, it.first) } + bodySlots.mapNotNull { slotAsk(setup, it) }
        prescore(setup, bodyAsks)
        scoreBaselines(setup, bodyAsks)
        bodyParties.forEach { (name, role) -> party(setup, s, name, role) }
        bodySlots.forEach { slot(setup, s, it) }
        val decoding = System.nanoTime()
        redecide(setup, s)
        timing("decode ms=${(System.nanoTime() - decoding) / NANOS_PER_MS} (includes the kind and household scoring of a changed answer)")
        // What each kept date and amount means, attached to the slot that holds it (see [readMeanings]).
        readMeanings(setup, s)
        traceFinal(setup, s)

        // The structured address of the addressee and of the sender, once the parties are settled (a letter with a recipient block only).
        // Read when the reading found an addressee: a document addressed to somebody has a recipient block, one with none (a receipt, a
        // screenshot of a chat) has not. What the model read decides, not the type, which is not known yet.
        val addresses = if (s.parties.any { it.role == PartyRole.ADDRESSEE.name }) readAddresses(setup, layout, s) else null

        // Everything a person needs to see is decided but the type: the parties, the slots and the addresses. The category, the extras, the
        // language and the free text are the second stage ([enrich]); the body session stays open for it, and so does what it was told of the
        // header. Until then the document is the neutral "Document", or the category a person gave.
        val given = forcedFamily?.let(schema::family)
        val provisional = given ?: schema.abstain ?: error("the schema has no abstain family")
        timing(
            String.format(
                Locale.ROOT, "scoring total batches=%d scores=%d ms=%d msPerScore=%.0f", scoreBatches, scoreCount, scoreMs,
                if (scoreCount > 0) scoreMs.toDouble() / scoreCount else 0.0,
            ),
        )
        letter = LetterSession(setup, zonesInPrefix, budget)
        return RawInterpretation(
            type = provisional.id, typeConfidence = if (given != null) "HIGH" else "LOW", language = null,
            parties = s.parties.take(StructuredGrammar.MAX_PARTIES), slots = s.slots, established = summary,
            topics = topics, layoutTemplate = setup.template.id, universalSlots = true,
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
            val (head, closing) = setup.frame(ZonePrompt.scoringSystem(setup.template), userContext() + ZonePrompt.bodyUser(summary, zonedText(setup, zonesInPrefix, budget)))
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
        val (head, closing) = setup.frame(ZonePrompt.writingSystem(setup.template), userContext() + bodyUser)
        return tryOpen(head, closing, "writing")
    }

    /**
     * What a person said the document is, as the one sentence every session of the reading opens with ("The user says this document is a
     * bill or an invoice."), or "" when nobody said. The category is a person's word for the document, given to the model as context; it
     * is never what decides which question is asked.
     */
    private var userLine = ""

    private fun userContext(): String = if (userLine.isEmpty()) "" else "$userLine\n\n"

    /** The sentence for the category of [familyId] (a family id or a legacy type id), or "" when the id names no category. */
    private fun userSentence(familyId: String?): String =
        schema.categoryOf(familyId)?.let { "The user says this document is ${it.phrase}." }.orEmpty()

    override val staged: Boolean = true

    /**
     * The second stage: the extras (scored in the body session, which an engine that still holds the same prefix does not decode again),
     * then, in a writing session (a small model obeys "answer Yes or No" over any question, so what is written is asked under an
     * instruction that only says to write what is asked), the language, the extras' names and the free text.
     */
    override suspend fun enrich(request: EnrichmentRequest): EnrichmentOutcome {
        val layout = request.layout ?: return EnrichmentOutcome.Failed("zones need the zoned layout")
        failures = 0
        userLine = userSentence(request.userFamily)
        try {
            val open = letter ?: run {
                val setup = ZoneSetup(engine, layout, request.offered, matcher, request.pageAspect)
                val zones = bodyZones(setup)
                inPrefix = emptySet()
                val budget = openBody(setup, zones, request.established) ?: return EnrichmentOutcome.Failed("the model could not read the letter")
                LetterSession(setup, zones, budget).also { letter = it }
            }
            // What the first stage read (the sender, the dates and amounts with their meanings), laid out as context for what is decided and
            // written now. The category comes first: it is scored from the read facts, and is a label for everything after it.
            val read = ReadFacts.block(request.facts, request.slots)
            // A profile that keeps the topics out of the first stage scores them here too: in the same batch as the category.
            val topicsHere = !topicsInFirstStage && request.topics.isEmpty()
            val decided = decideCategory(request, read, topicsHere)
            // The family's hint says what matters in this kind of document: it steers which read fields are marked as key information;
            // the model still decides, code only verifies. The facts beyond the read fields are written later ([KeyInfoWriter]).
            val hint = (decided.family ?: request.documentTypeId?.let { schema.family(it) } ?: schema.abstain)?.hint
            val keySlots = pickKeySlots(hint, request.slots)
            // The topics of such a profile: from the category's batch, or (a category a person gave decides nothing to score) on their own.
            val lateTopics = if (!topicsHere) null else decided.topics ?: classifier().topics(tail)
            // What the reader has to do is scored too, in the same body session: a kind is chosen from the catalogue, nothing is written.
            val decidedFamily = decided.family ?: request.documentTypeId?.let(schema::family)
            val actions = readActions(open.setup, request, decidedFamily)
            // What the letter reports (an approval, a payment demand ...) is scored in the same session, from the registry of event kinds:
            // one batch against the content-free baseline, "information" when no kind passes its margin. Null when it could not be scored.
            val eventKind = EventKindReader({ name, questions -> scoreBatch(name, "", questions) }, profile.events, trace = { traceLines += it })
                .read(decidedFamily?.takeIf { it.scored }?.description)
            // The letter as plain text: no zone hints and no summary of the header, which a small model copies instead of the letter.
            val writing = switchToWriting(open.setup, ZonePrompt.bodyUser("", open.setup.zoned.render(open.zonesInPrefix, open.budget)))
            if (!writing) {
                return EnrichmentOutcome.Done(
                    Enrichment(
                        language = null, extras = emptyList(), text = null, textError = "the model could not read the letter again", topics = lateTopics,
                        actions = actions, keySlots = keySlots, type = decided.family?.id, typeConfidence = decided.confidence,
                        event = eventKind?.let { EventReading(it.kindId) },
                    ),
                )
            }
            val language = ask(QuestionnairePrompt.language())?.let { AnswerReader.language(it) }
            val text = ZoneFreeText.write(includeSummary = false) { q -> ask(q) }
            val written = (text as? TextOutcome.Written)?.text
            // The summary rests on verified facts only: the subject line counts as one when it is printed in the letter.
            val subject = written?.subject?.takeIf { QuoteVerifier.verifyCopiedLine(it, request.ocrText) != null }
            val facts = SummaryFacts.of(decided.family?.id ?: request.documentTypeId ?: schema.abstain?.id.orEmpty(), request.facts, subject)
            val summary = SummaryWriter(FramedSession()).write(facts, request.ocrText, language)
            // The open key information: one more generation in the same session (the letter is still the prefix), shown the read fields so
            // it does not repeat them. Every value is checked against the letter; a failure leaves none.
            val extras = KeyInfoWriter(FramedSession("text:keyinfo")).write(readFields(request), request.ocrText, language)
            // The specific name the title uses: written last, knowing what was read and what the category is, and kept only when every
            // number and name in it is printed in the letter ([DocumentNameVerifier]); none otherwise.
            val name = DocumentNameWriter(FramedSession("text:name")).write(read, categoryContext(decided), request.ocrText, language)
            // The event's title: written last, in the document's language, kept only when grounded like the name ([DocumentNameVerifier]).
            val event = eventKind?.let {
                EventReading(it.kindId, EventTitleWriter(FramedSession("text:eventtitle")).write(read, EventKinds.DEFAULT.byId(it.kindId), request.ocrText, language))
            }
            return EnrichmentOutcome.Done(
                Enrichment(
                    language = language, extras = extras, text = written,
                    textError = (text as? TextOutcome.Failed)?.reason, summary = summary, topics = lateTopics, actions = actions,
                    keySlots = keySlots, type = decided.family?.id, typeConfidence = decided.confidence, name = name, event = event,
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
    private suspend fun readActions(setup: ZoneSetup, request: EnrichmentRequest, family: DocFamily?): List<ActionItem>? {
        // What the reading decided the document is goes into the gate question as context: the model's own conclusion, nothing written here.
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

    /**
     * One line per scored question, for fitting the margins from normal use (the pipeline logs it under `DocProcessing` in a debuggable
     * build only, because it carries the winner's text, cut to [TRACE_TEXT_CHARS] characters): the question, the zones, how many candidates
     * were scored and in which tier the winner was (`first` is the first tier's share), the pick with its zones, kind and score, the
     * runner-up's score, the content-free baseline and the threshold the pick had to beat, and the winner's text.
     */
    private fun traceAsk(setup: ZoneSetup, name: String, zones: List<LetterZone>, read: Scoring, threshold: Double, baseline: Double?, best: Int?) {
        val asked = zones.joinToString("+") { it.tag }
        val base = baseline?.let { String.format(Locale.ROOT, "%+.2f", it) } ?: "-"
        val floor = String.format(Locale.ROOT, "%+.2f", threshold)
        if (best == null) {
            traceLines += "ask $name zones=$asked cands=${read.cands.size} first=${read.firstCount} tier=${read.tier} pick=none baseline=$base floor=$floor"
            return
        }
        val second = read.scores.indices.filter { it != best }.maxOfOrNull { read.scores[it] }
        val c = read.cands[best]
        val text = c.raw.replace('\n', ' ').trim().let { if (it.length > TRACE_TEXT_CHARS) it.take(TRACE_TEXT_CHARS - 1) + "…" else it }
        traceLines += String.format(
            Locale.ROOT, "ask %s zones=%s cands=%d first=%d tier=%d pick=%s kind=%s in=%s best=%+.2f second=%s baseline=%s floor=%s text=«%s»",
            name, asked, read.cands.size, read.firstCount, read.tierOf(best), c.id, c.kind.name,
            setup.zoned.zonesOfCandidate(c.id).joinToString("+") { it.tag }, read.scores[best], second?.let { String.format(Locale.ROOT, "%+.2f", it) } ?: "-",
            base, floor, text,
        )
    }

    /**
     * The candidates (from index [from] on) that are a field label or a heading rather than a party, found by one scored batch over the
     * question's own zone block: the [MAX_LABEL_CHECKS] best-scored candidates above [threshold] and a made-up name, each asked "is this a field
     * label or a heading, not a person or an organisation?". A candidate is a label only when it beats the made-up name by
     * [ScoringProfile.fieldLabelMargin]; a profile with no margin never asks. The model scores, the margin decides: no word is looked at.
     */
    private suspend fun fieldLabels(
        setup: ZoneSetup, name: String, block: String, cands: List<Candidate>, scores: List<Double>, from: Int, threshold: Double,
        allowed: (Candidate) -> Boolean,
    ): Set<Int> {
        val margin = profile.fieldLabelMargin ?: return emptySet()
        val top = scores.indices.filter { it >= from && scores[it] > threshold && allowed(cands[it]) }.sortedByDescending { scores[it] }.take(MAX_LABEL_CHECKS)
        if (top.isEmpty()) return emptySet()
        val statement = ScoringDescriptions.FIELD_LABEL
        val questions = top.map { ZonePrompt.scoringQuestion(cands[it].raw.replace('\n', ' '), setup.zoned.context(cands[it]), statement) } +
            ZonePrompt.scoringQuestion(ScoringDescriptions.PARTY_BASELINE_NAME, null, statement)
        val answers = scoreBatch("label:$name", block, questions) ?: return emptySet()
        val floor = answers.last() + margin
        return top.indices.filter { answers[it] > floor }.map { top[it] }.toSet()
    }

    private fun traceFinal(setup: ZoneSetup, s: State) {
        fun where(id: String) = setup.zoned.zonesOfCandidate(id).joinToString("+") { it.tag }
        s.parties.forEach { traceLines += "final party ${it.role} id=${it.id} kind=${it.kind} in=${where(it.id)} conf=${it.confidence}" }
        s.slots.forEach { (k, v) -> traceLines += "final slot $k id=${v.id ?: v.ids.joinToString(",")} in=${v.id?.let(::where).orEmpty()} conf=${v.confidence}" }
    }

    /**
     * Marks which of the stored (read) slot values the reader needs, given the family's hint: scored in the body session, the best
     * [ScoringDescriptions.MAX_KEY_INFO] above the `keyslots` threshold. These are read fields, ordered for the "Key information" section;
     * the facts that are NOT read fields are the generated ones ([KeyInfoWriter]), so the scored extras (values picked from the page by a
     * lean Yes and named by one ask each) no longer exist. Null when none were scored, so stored marks stay.
     */
    private suspend fun pickKeySlots(hint: String?, slots: List<TicketSlot>): List<KeySlot>? {
        val asked = slots.take(ScoringDescriptions.MAX_KEY_SLOT_SCORES)
        if (asked.isEmpty()) return null
        // The batch keeps its recorded name, so a replay finds the questions it holds.
        val scores = scoreBatch(ScoringDescriptions.EXTRAS_ASK, "", asked.map { ZonePrompt.keySlotQuestion(it.label, it.value, hint) }) ?: return null
        val slotThreshold = profile.threshold(ScoringDescriptions.KEY_SLOTS_ASK)
        return asked.indices.filter { scores[it] > slotThreshold }.sortedByDescending { scores[it] }
            .take(ScoringDescriptions.MAX_KEY_INFO).map { KeySlot(asked[it].key, scores[it].toFloat()) }
    }

    /**
     * The read fields as (label, value): what the first stage verified (the sender, the addressee, the amount ...) and the stored slot
     * values, as the key-information step shows them to the model and checks its facts against.
     */
    private fun readFields(request: EnrichmentRequest): List<Pair<String, String>> =
        (request.slots.map { it.label to it.value } + request.facts.entries.map { (role, value) -> role to value })
            .filter { it.second.isNotBlank() }.distinctBy { it.second.trim() }

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

    /** The category the second stage settled on: [family] (the category's stored family, or the abstain family) and its confidence word; both null when it could not be scored. */
    private class Decided(val family: DocFamily?, val confidence: String?, val topics: List<String>? = null)

    /**
     * What broad kind of document this is, decided from what was read: the category among those a document of the request's direction can be
     * ([ExtractionSchema.categoryFamilies]), or the neutral "Document" when none passes its threshold, runner-up margin and baseline. A
     * category a person gave is not decided again: it is the answer. A scoring the engine failed decides nothing, so the stored type stays.
     */
    private suspend fun decideCategory(request: EnrichmentRequest, read: String, includeTopics: Boolean): Decided {
        val given = request.userFamily?.let(schema::family)
        if (given != null) {
            traceLines += "category given by the user -> ${given.id}"
            return Decided(given, "HIGH")
        }
        val result = classifier().classify(request.direction, tail, includeTopics = includeTopics, read = read)
        if (result == null) {
            traceLines += "category could not be scored"
            return Decided(null, null)
        }
        val offered = result.scores.filterKeys { it.startsWith("family:") }.entries.sortedByDescending { it.value }
        traceLines += "category offered=${offered.size} read=${read.isNotEmpty()} " +
            offered.take(3).joinToString(" ") { it.key.removePrefix("family:") + String.format(Locale.ROOT, "=%+.2f", it.value) } +
            result.scores[FamilyClassifier.BASELINE_KEY]?.let { String.format(Locale.ROOT, " baseline=%+.2f", it) }.orEmpty() +
            " -> ${result.family.id}"
        return Decided(result.family, result.familyConfidence, if (includeTopics) result.topics else null)
    }

    /** The category as one sentence for the name's prompt: the person's own word, else what the reading decided; empty for the neutral "Document". */
    private fun categoryContext(decided: Decided): String {
        if (userLine.isNotEmpty()) return userLine
        return decided.family?.let { schema.categoryOf(it.id) }?.let { "The category of this document is ${it.phrase}." }.orEmpty()
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
    private class Ask(
        val name: String, val zones: List<LetterZone>, val cands: List<Candidate>, val what: String, val labelled: Boolean = false,
        /** The made-up value its content-free baseline is asked about ([ScoringDescriptions.BASELINE_PROBES]); null for a question that has none. */
        val probe: String? = null,
        /**
         * The cascade (see [ZonePlan.tiers]): [cands] are the first tier, the candidates of the preferred zones, scored first; [rest] are the
         * other zones' candidates, scored only when no first-tier candidate beats what the question needs. Empty for a question with no
         * second tier, and for one whose preferred zones hold no candidate (then [cands] is already everything and [tier] is 2).
         */
        val rest: List<Candidate> = emptyList(),
        /** The tier [cands] belong to: 1 the preferred zones, 2 the other zones (nothing was printed in a preferred one), 0 not tiered. */
        val tier: Int = 0,
    )

    /** What one question scored: the candidates and scores of every tier that was scored, and how many of them are the first tier's. */
    private class Scoring(val cands: List<Candidate>, val scores: List<Double>, val firstCount: Int, val tier: Int) {
        /** The tier the candidate at [index] was scored in (0 for a question with no tiers). */
        fun tierOf(index: Int): Int = if (tier == 0) 0 else if (index < firstCount) tier else 2
    }

    /**
     * Whether [slot] may be none for this document (see [ScoringProfile.isOptional]). The type is not known while the slots are read, so no
     * slot is "its own": every slot beyond the core must be one the model leans Yes to ([ScoringProfile.optionalUnlessOwn]).
     */
    private fun optional(slot: SlotKey): Boolean = profile.isOptional(QuestionNames.slot(slot.json), own = false)

    /** The threshold a slot question abstains under: its own, or the optional level for a value this document may not have. */
    private fun slotThreshold(name: String, slot: SlotKey?): Double =
        if (slot == null) profile.threshold(name) else profile.slotThreshold(name, own = false)

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

    /**
     * The zones whose text a question's prompt shows: the zones it prefers, when one of its candidates is printed there; else its fallback
     * zones (the sender's name is sometimes only in the small print at the foot); else the zones of its candidates that the session's
     * prefix already holds (a candidate carries its own row and the rows around it, so a prompt never needs more text than that).
     */
    private fun askedZones(setup: ZoneSetup, preferred: List<LetterZone>, fallback: List<LetterZone>, cands: List<Candidate>): List<LetterZone> {
        val zoned = setup.zoned
        fun holdsOne(zones: List<LetterZone>) = cands.any { c -> zoned.zonesOfCandidate(c.id).any { it in zones } }
        return when {
            holdsOne(preferred) -> preferred
            holdsOne(fallback) -> fallback
            else -> cands.flatMap { zoned.zonesOfCandidate(it.id) }.distinct().filter { it in inPrefix && zoned.hasText(it) }
        }
    }

    /**
     * The party question [name]: every name of the page, those in the zones the template prefers first, then those in its fallback zones
     * ([SlotPlacements.partyFallback]), then the rest ([ZonePlan.offer]); null only when the page holds no name.
     */
    private fun partyAsk(setup: ZoneSetup, name: String): Ask? {
        val zoned = setup.zoned
        val preferred = setup.plan.zones(name).filter { zoned.hasText(it) }
        val fallback = SlotPlacements.partyFallback(name).map { zoned.mapped(it) }.distinct().filter { zoned.hasText(it) && it !in preferred }
        // Every name is scored, whatever was decided before: what is scored then does not depend on the
        // thresholds, which is what lets a recording be re-decided offline. The one exclusion code makes (the sender is
        // not also the addressee, the routing person or the mailbox) is applied to the choice.
        // A name that is a cell of a table (a column header such as "Einzelpreis € Gesamt €", a position) is never a party or a person:
        // by where it is printed, not by any word.
        val names = zoned.offered.rows.map { it.candidate }.filter { it.kind == CandidateKind.NAME && !zoned.isTableCell(it) }
        val tiers = setup.plan.tiers(names, preferred, fallback)
        val all = tiers.first + tiers.rest
        if (all.isEmpty()) return null
        // The zones the prompt shows are those of every candidate (the first tier's block is also the second tier's, so the prefix is reused).
        return tiered(name, askedZones(setup, preferred, fallback, all), tiers, ScoringDescriptions.ofRole(name), probe = ScoringDescriptions.PARTY_BASELINE_NAME)
    }

    /** An [Ask] over [tiers]: the first tier alone when there is one (the rest waits), else the rest as the only tier. */
    private fun tiered(name: String, zones: List<LetterZone>, tiers: ZonePlan.Tiers, what: String, labelled: Boolean = false, probe: String? = null): Ask =
        if (tiers.first.isEmpty()) {
            Ask(name, zones, tiers.rest, what, labelled, probe, tier = 2)
        } else {
            Ask(name, zones, tiers.first, what, labelled, probe, rest = tiers.rest, tier = 1)
        }

    /**
     * Whether the slot question [slot] is a cascade: its candidates are scored in tiers, the preferred zones' first ([ZonePlan.tiers]).
     * The dates and the references: a value of those kinds printed in a zone where the template expects it is not to be outvoted by one
     * from anywhere on the page (the due date by the period start, the letter date by the due date).
     */
    private fun cascades(slot: SlotKey): Boolean = when (slot.kind) {
        SlotKind.DATE, SlotKind.DEADLINE, SlotKind.REFERENCE, SlotKind.REFERENCE_LIST -> true
        else -> false
    }

    /**
     * The slot question of [slot]: every candidate of its kind on the page, those in the zones the template places it on first
     * ([ZonePlan.offer]). A number the document may not have is still only taken from a value with a printed label of its own.
     */
    private fun slotAsk(setup: ZoneSetup, slot: SlotKey): Ask? {
        if (slot.kind == SlotKind.ACTION) return null
        val zoned = setup.zoned
        val name = QuestionNames.slot(slot.json)
        val preferred = setup.plan.zones(name, slot).filter { zoned.hasText(it) }
        val isReference = slot.kind == SlotKind.REFERENCE || slot.kind == SlotKind.REFERENCE_LIST
        // A number the document may not have is taken only from a value that prints a label of its own: a digit-and-letter run found by
        // shape alone (a signature fragment, a serial) has nothing printed beside it that says what it is, so a small model's lean Yes on
        // an optional slot is never enough to give it a name such as "Case number".
        fun offered(rows: List<Candidate>) = rows.filter {
            it.kind in slot.kind.candidates && !(isReference && (isFooterShape(zoned, it) || (optional(slot) && it.attrs["shape"] != null)))
        }
        val rows = offered(zoned.offered.rows.map { it.candidate })
        val labelled = isReference && optional(slot)
        if (cascades(slot)) {
            val tiers = setup.plan.tiers(rows, preferred)
            val all = tiers.first + tiers.rest
            if (all.isEmpty()) return null
            return tiered(name, askedZones(setup, preferred, emptyList(), all), tiers, ScoringDescriptions.ofSlot(slot), labelled, baselineProbe(slot, isReference))
        }
        val cands = setup.plan.offer(rows, preferred)
        if (cands.isEmpty()) return null
        return Ask(
            name, askedZones(setup, preferred, emptyList(), cands), cands, ScoringDescriptions.ofSlot(slot), labelled = labelled,
            probe = baselineProbe(slot, isReference),
        )
    }

    /**
     * The made-up value a slot's content-free baseline is asked about: a reference for a reference slot, and for a date or an amount slot that
     * is not in the core (the core's are always asked and always have a candidate worth taking; the others belong to some kinds of document,
     * and a document of another kind has none, which is "none" for the question).
     */
    private fun baselineProbe(slot: SlotKey, isReference: Boolean): String? = when {
        isReference -> ScoringDescriptions.REFERENCE_BASELINE_VALUE
        slot in Slots.CORE -> null
        slot.kind == SlotKind.AMOUNT -> ScoringDescriptions.AMOUNT_BASELINE_VALUE
        slot.kind == SlotKind.DATE || slot.kind == SlotKind.DEADLINE -> ScoringDescriptions.DATE_BASELINE_VALUE
        else -> null
    }

    /** The scores of [ask]'s candidates: the ones [prescore] computed, else scored now as a batch under [block]. */
    private suspend fun scored(setup: ZoneSetup, ask: Ask, block: String): List<Double>? =
        prescored.remove(ask.name) ?: scoreBatch(ask.name, block, questionsOf(setup, ask, ask.cands))

    private fun questionsOf(setup: ZoneSetup, ask: Ask, cands: List<Candidate>): List<String> =
        cands.map { ZonePrompt.scoringQuestion(it.raw.replace('\n', ' '), setup.zoned.context(it), ask.what, if (ask.labelled) setup.zoned.printedLabel(it) else null) }

    /**
     * The cascade of [ask]: its first tier is scored ([first], by [scored]); when no candidate of it is [taken] above [threshold] (the
     * question's threshold, raised to its content-free baseline plus margin), the other zones' candidates ([Ask.rest]) are scored too, in
     * the same zone block, and the answer is chosen among both tiers. When a first-tier candidate is taken the rest is never scored, so a
     * candidate from another zone can neither outvote it nor cost anything. A scoring the engine failed leaves the first tier alone.
     */
    private suspend fun cascade(
        setup: ZoneSetup, ask: Ask, block: String, first: List<Double>, threshold: Double, taken: (Candidate) -> Boolean = { true },
        /** The indices (from [from] on) of the candidates that are not what is asked for at all: a field label (see [fieldLabels]). */
        vet: suspend (cands: List<Candidate>, scores: List<Double>, from: Int) -> Set<Int> = { _, _, _ -> emptySet() },
    ): Scoring {
        var cands = ask.cands
        var scores = first
        var firstCount = cands.size
        fun drop(gone: Set<Int>) {
            if (gone.isEmpty()) return
            firstCount -= gone.count { it < firstCount }
            cands = cands.filterIndexed { i, _ -> i !in gone }
            scores = scores.filterIndexed { i, _ -> i !in gone }
        }
        drop(vet(cands, scores, 0))
        val passes = scores.indices.any { scores[it] > threshold && taken(cands[it]) }
        if (ask.rest.isNotEmpty() && !passes) {
            val more = scoreBatch(ask.name, block, questionsOf(setup, ask, ask.rest))
            if (more != null) {
                val from = cands.size
                cands = cands + ask.rest
                scores = scores + more
                drop(vet(cands, scores, from))
            }
        }
        return Scoring(cands, scores, firstCount, ask.tier)
    }

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
     * Scores the names of the page for [name], the zones the template prefers first (see [partyAsk]).
     *
     * @return whether any name was scored (false: the page holds no name).
     */
    private suspend fun party(setup: ZoneSetup, s: State, name: String, role: PartyRole): Boolean {
        val ask = partyAsk(setup, name) ?: return false
        val zones = ask.zones
        val block = block(setup, zones)
        val first = scored(setup, ask, block) ?: return true
        // "None of these" is an answer: a name is taken only when it also beats a made-up name asked the same way over the same zones, so the
        // threshold of the question is raised to that level (the decoder, which re-decides from the scores, then abstains the same way).
        val threshold = maxOf(profile.threshold(name), baselineFloor(ask) ?: Double.NEGATIVE_INFINITY)
        // The cascade: the other zones' names are scored only when no name of the preferred zones is taken (the sender is not also the addressee).
        val allowedName = { c: Candidate -> role == PartyRole.SENDER || c.id != s.senderId }
        val read = cascade(setup, ask, block, first, threshold, allowedName) { cands, scores, from -> fieldLabels(setup, name, block, cands, scores, from, threshold, allowedName) }
        val cands = read.cands
        val scores = read.scores
        if (cands.isEmpty()) return true
        collect(name, cands, scores, role = role, slot = null, block = block, threshold = threshold)
        val allowed = scores.indices.filter { role == PartyRole.SENDER || cands[it].id != s.senderId }
        val ranked = allowed.sortedByDescending { scores[it] }
        traceAsk(setup, name, zones, read, threshold, baselines[name], ranked.firstOrNull())
        val best = ranked.firstOrNull() ?: return true
        if (scores[best] <= threshold) return true
        val (confidence, note) = confidenceOf(scores[best], ranked.getOrNull(1)?.let { scores[it] }, ranked.size)
        addParty(setup, s, name, role, cands[best], block, confidence, note, ranked.drop(1).take(MAX_ALTERNATIVES).map { RawAlternative(cands[it].id, scores[it]) })
        return true
    }

    /** The content-free baselines scored ahead by [scoreBaselines], by question name. */
    private val baselines = HashMap<String, Double>()

    /**
     * Scores ahead the content-free baseline of every question of [asks] that has a margin ([ScoringProfile.baselineMargin]): the same
     * question about a made-up value that is nowhere in the letter ([Ask.probe]: a name, a reference), over the same zone block. The questions
     * that share a block and a made-up value are one batch (the block is decoded once), so "none" costs one statement per question.
     */
    private suspend fun scoreBaselines(setup: ZoneSetup, asks: List<Ask>) {
        val wanted = asks.filter { it.probe != null && profile.baselineMargin(it.name) != null }
        for ((key, group) in wanted.groupBy { block(setup, it.zones) to it.probe!! }) {
            val (block, probe) = key
            val scores = scoreBatch("baseline:" + group.joinToString("+") { it.name }, block, group.map { ZonePrompt.scoringQuestion(probe, null, it.what) }) ?: continue
            group.forEachIndexed { i, ask -> baselines[ask.name] = scores[i] }
        }
    }

    /**
     * The score a candidate of [ask] must be above to be taken: its content-free baseline plus the profile's margin. Null when the profile sets
     * no margin for this question, and when the baseline could not be scored (nothing is then known against the value, so it is kept as before).
     */
    private fun baselineFloor(ask: Ask): Double? {
        val margin = profile.baselineMargin(ask.name) ?: return null
        val baseline = baselines[ask.name] ?: return null
        traceLines += String.format(Locale.ROOT, "%s baseline %+.2f margin %.2f -> a value needs more than %+.2f", ask.name, baseline, margin, baseline + margin)
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
     * Scores every candidate of [slot]'s kind on the page. The placement is a prior: the candidates in the zones the template places
     * the slot on come first, the others follow (see [slotAsk]).
     *
     * @return whether anything was scored.
     */
    private suspend fun slot(setup: ZoneSetup, s: State, slot: SlotKey): Boolean {
        if (slot.kind == SlotKind.ACTION) return true
        val ask = slotAsk(setup, slot) ?: return false
        val name = ask.name
        val asked = ask.zones
        val block = block(setup, asked)
        val first = scored(setup, ask, block) ?: return true
        // "None" is an answer for a reference too: it is taken only when it beats a made-up reference over the same zones by the margin.
        val threshold = maxOf(slotThreshold(name, slot), baselineFloor(ask) ?: Double.NEGATIVE_INFINITY)
        // The cascade: the other zones' candidates are scored only when no candidate of the zones the template places the slot on is taken.
        val read = cascade(setup, ask, block, first, threshold)
        val cands = read.cands
        val scores = read.scores
        if (slot.kind != SlotKind.REFERENCE_LIST) collect(name, cands, scores, role = null, slot = slot, block = block, threshold = threshold)
        val order = scores.indices.sortedByDescending { scores[it] }
        val best = order.first()
        traceAsk(setup, name, asked, read, threshold, baselines[name], best)
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

    // ── what a date or an amount means ──

    /**
     * Attaches to each date and amount slot the reading kept what its value MEANS ([ValueMeaningReader]), in the open body session. The slots
     * were decided above and stay as decided: the meaning is an attribute of the slot's value, so there is one decision, never two that
     * could disagree. A value no meaning beats its baseline by is "other" and gets none; a slot that holds a period quoted in words has no
     * candidate to ask about.
     */
    private suspend fun readMeanings(setup: ZoneSetup, s: State) {
        val zoned = setup.zoned
        val targets = scored.mapNotNull { a ->
            val slot = a.slot ?: return@mapNotNull null
            val kind = MeaningKind.of(slot.kind) ?: return@mapNotNull null
            val id = s.slots[slot.json]?.id ?: return@mapNotNull null
            val c = a.cands.firstOrNull { it.id == id } ?: return@mapNotNull null
            ValueMeaningReader.Target(slot.json, kind, c.raw.replace('\n', ' '), zoned.context(c), a.block)
        }
        if (targets.isEmpty()) return
        val meanings = ValueMeaningReader({ name, shared, questions -> scoreBatch(name, shared, questions) }, profile, trace = { traceLines += it }).read(targets)
        val slotsByKey = scored.mapNotNull { it.slot }.associateBy { it.json }
        for ((key, meaning) in meanings) {
            val slot = slotsByKey[key]
            // What the value means decides whether it may stay in the slot: a due-date slot holding "the first day of a period", or a
            // letter-date slot holding "the date by which the reader must pay", is empty (the value is not given to another slot by a rule).
            if (slot != null && MeaningVerdict.contradicts(slot, meaning)) {
                traceLines += "meaning $key -> ${meaning.id} contradicts the slot: the slot is left empty"
                s.slots.remove(key)
                continue
            }
            s.slots[key]?.let { s.slots[key] = it.copy(meaning = meaning.id) }
        }
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

        /** How many of a question's best names are asked whether they are a field label, and how much of the winner's text a trace line shows. */
        private const val MAX_LABEL_CHECKS = 2
        private const val TRACE_TEXT_CHARS = 40
        private const val MAX_FAILURES = 3
        private const val MAX_OPEN_ATTEMPTS = 3
        private const val FAILED_RAW_CHARS = 300
        private const val OVERHEAD_CHARS = 700
    }
}
