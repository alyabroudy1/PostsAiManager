package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.AmountConsistency
import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.DateValidator
import com.postsaimanager.core.domain.extraction.candidates.Validation
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner.Caps
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner.Check
import java.time.LocalDate

/**
 * "Code verifies": checks the model's answers and makes the confidence honest. It never replaces an
 * answer with one of its own; a failed check keeps the model's value, caps its confidence and (for a
 * blocking check) flags it.
 *
 * What it checks, none of it language specific:
 * - **Membership.** Every id must be an offered candidate of the right kind, else the answer is
 *   dropped and listed in [Diagnostics.rejections].
 * - **Validation.** The candidate's own verdict (IBAN checksum, calendar date within the plausible
 *   range around the letter date, an amount's currency). When code could not find the letter date,
 *   the letter date the model chose (once it is itself a plausible date) anchors the other dates.
 * - **Self-consistency.** The role the model gave a value against what its slot expects; a total
 *   that is the net or the VAT part of a net + VAT = gross triple present in the letter; a due date
 *   before the letter date.
 * - **Position.** A sender taken from the address field, or an addressee from the letterhead, is
 *   the model contradicting the layout.
 * - **Quotes.** Every quoted name, rule, summary sentence, subject and extra must be found in the OCR
 *   text ([QuoteVerifier]); otherwise it is dropped, or for a summary marked as AI-written.
 * - **Roles.** The sender is never the addressee, the co-addressee, the routing person or the mailbox.
 * - **Extras.** At most [StructuredGrammar.MAX_EXTRAS]; an id already used by a slot or a party, an
 *   id of NONE without a verifiable value, and a phone, e-mail or BIC the model was not sure of
 *   are dropped.
 *
 * The final confidence comes from [ConfidenceCombiner].
 */
class SelectionVerifier(
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
) : ResultVerifier {

    override fun verify(raw: RawInterpretation, text: RawText?, context: VerificationContext): ExtractionV2Result =
        Run(schema, raw, text, context).execute()

    private class Run(
        private val schema: ExtractionSchema,
        private val raw: RawInterpretation,
        private val text: RawText?,
        private val ctx: VerificationContext,
    ) {
        private val rejections = mutableListOf<String>()
        private val conflicts = mutableListOf<String>()
        private val usedCandidateIds = mutableSetOf<String>()
        private var anchor: LocalDate? = ctx.candidates.letterDate

        fun execute(): ExtractionV2Result {
            val type = schema.type(raw.type)
            if (type == null) rejections += "type '${raw.type}' is not in the schema"
            val docType = type ?: schema.type("other")

            // The letter date the model chose anchors the other dates when code could not find one.
            if (anchor == null && docType != null) anchor = anchorFromModel(docType)

            val slots = LinkedHashMap<SlotKey, SlotValue>()
            val lists = LinkedHashMap<SlotKey, List<SlotValue>>()
            if (docType != null) {
                for (slot in docType.slots) {
                    val answer = raw.slots[slot.json] ?: continue
                    if (slot.kind == SlotKind.REFERENCE_LIST) {
                        val values = answer.ids.mapNotNull { id ->
                            candidateValue(slot, id, answer.confidence, null, CandidateKind.REFERENCE)
                        }
                        if (values.isNotEmpty()) lists[slot] = values
                    } else {
                        slotValue(slot, answer)?.let { slots[slot] = it }
                    }
                }
                raw.slots.keys.filter { key -> docType.slots.none { it.json == key } }.forEach {
                    rejections += "slot '$it' does not belong to type ${docType.id}"
                }
            }

            val parties = verifyParties()
            val extras = verifyExtras()
            val freeText = text?.let { verifyFreeText(it) } ?: FreeText()

            return ExtractionV2Result(
                documentType = docType,
                typeConfidence = ConfidenceCombiner.aiScore(raw.typeConfidence),
                otherLabel = text?.otherLabel?.trim()?.ifBlank { null },
                language = raw.language?.trim()?.lowercase()?.ifBlank { null },
                slots = slots,
                slotLists = lists,
                parties = parties,
                freeText = freeText,
                extras = extras,
                letterDate = anchor,
                diagnostics = Diagnostics(
                    candidateCount = ctx.candidates.candidates.size,
                    offeredCount = ctx.offered.size,
                    offeredDropped = ctx.offered.dropped.mapKeys { it.key.name },
                    modelCalled = true,
                    modelUsed = true,
                    textError = ctx.textError,
                    rawAnswer = ctx.rawAnswer,
                    rawText = ctx.rawText,
                    rejections = rejections,
                    conflicts = conflicts,
                    layoutCharsSent = ctx.layoutCharsSent,
                    layoutCharsTotal = ctx.layoutCharsTotal,
                    pagesRead = ctx.pagesRead,
                    totalPages = ctx.totalPages,
                    grammar = ctx.grammar,
                    prompt = ctx.prompt,
                ),
            )
        }

        // ── slots ────────────────────────────────────────────────────────────────

        private fun anchorFromModel(type: DocType): LocalDate? {
            val letterSlot = type.slots.firstOrNull { it.canonical == Canonical.DOCUMENT_DATE } ?: return null
            val id = raw.slots[letterSlot.json]?.id ?: return null
            val c = ctx.offered.get(id) ?: return null
            if (c.kind != CandidateKind.DATE && c.kind != CandidateKind.DATETIME) return null
            return parseDate(c.normalized)?.takeIf { it.year in 2000..2100 }
        }

        private fun slotValue(slot: SlotKey, answer: RawSlot): SlotValue? = when (slot.kind) {
            SlotKind.AMOUNT, SlotKind.DATE ->
                candidateValue(slot, answer.id, answer.confidence, answer.role, *slot.kind.candidates)
            SlotKind.DEADLINE ->
                if (answer.rule != null && answer.id == null) {
                    quotedValue(slot, answer.rule, answer.confidence, answer.role)
                } else {
                    candidateValue(slot, answer.id, answer.confidence, answer.role, *slot.kind.candidates)
                }
            SlotKind.IBAN, SlotKind.REFERENCE ->
                candidateValue(slot, answer.id, answer.confidence, null, *slot.kind.candidates)
            SlotKind.NAME -> nameValue(slot, answer.id, answer.confidence)
            SlotKind.ACTION -> actionValue(slot, answer)
            SlotKind.REFERENCE_LIST -> null // handled by the caller
        }

        private fun actionValue(slot: SlotKey, answer: RawSlot): SlotValue? {
            val id = answer.id?.trim() ?: return null
            if (id !in SlotKey.ACTIONS) {
                rejections += "${slot.json}: '$id' is not one of ${SlotKey.ACTIONS}"
                return null
            }
            val ai = ConfidenceCombiner.aiScore(answer.confidence)
            return SlotValue(
                slot = slot, candidateId = null, value = id, normalized = id, page = null, bbox = null,
                evidence = "", origin = SlotOrigin.MODEL_CHOICE, aiConfidence = ai, confidence = ai,
                validation = Validation.Valid,
            )
        }

        /** [id] must be an offered candidate of one of [kinds]; otherwise it is rejected. */
        private fun candidateValue(
            slot: SlotKey,
            id: String?,
            aiWord: String?,
            role: String?,
            vararg kinds: CandidateKind,
        ): SlotValue? {
            val cid = id?.trim()
            if (cid.isNullOrEmpty() || cid == StructuredGrammar.NONE) return null
            val c = ctx.offered.get(cid)
            if (c == null) {
                rejections += "${slot.json}: '$cid' is not an offered candidate"
                return null
            }
            if (kinds.isNotEmpty() && c.kind !in kinds) {
                rejections += "${slot.json}: $cid is a ${c.kind}, not one of ${kinds.toList()}"
                return null
            }
            usedCandidateIds += c.id
            val checks = mutableListOf<Check>()
            checks += validationCheck(effectiveValidation(c, slot), c.kind)
            checks += repairCheck(c)
            checks += roleCheck(slot, role)
            checks += dateOrderCheck(slot, c)
            checks += amountConsistencyCheck(slot, c)
            return build(slot, c, aiWord, role, checks)
        }

        private fun build(slot: SlotKey?, c: Candidate, aiWord: String?, role: String?, checks: List<Check>): SlotValue {
            val ai = ConfidenceCombiner.aiScore(aiWord)
            val combined = ConfidenceCombiner.combine(ai, checks)
            return SlotValue(
                slot = slot, candidateId = c.id, value = c.raw.trim(), normalized = c.normalized,
                page = c.page, bbox = c.bbox, evidence = c.evidence, origin = SlotOrigin.MODEL_CHOICE,
                aiConfidence = ai, confidence = combined.final, validation = c.validation,
                blocked = combined.blocked, notes = combined.notes, role = role,
            )
        }

        private fun quotedValue(slot: SlotKey, quote: String, aiWord: String?, role: String?): SlotValue? {
            val q = quote.trim()
            val verified = QuoteVerifier.verify(q, ctx.ocrText)
            if (verified == null) {
                rejections += "${slot.json}: the quote '${q.take(40)}' is not in the letter"
                return null
            }
            val ai = ConfidenceCombiner.aiScore(aiWord)
            // A period in words: once the quote is in the letter, read its number and unit if it has them.
            val period = RelativePeriod.parse(q)
            val checks = mutableListOf(quoteCheck(verified.match), roleCheck(slot, role))
            if (period != null && !period.plausible) {
                checks += Check.Cap(Caps.INVALID, "failed a check: implausible period ${period.n}")
            }
            val combined = ConfidenceCombiner.combine(ai, checks)
            return quotedSlotValue(slot, q, ai, combined, verified.match, role, normalized = period?.iso ?: q)
        }

        private fun quotedSlotValue(
            slot: SlotKey?,
            q: String,
            ai: Float,
            combined: ConfidenceCombiner.Combined,
            match: QuoteMatch,
            role: String? = null,
            normalized: String = q,
        ) = SlotValue(
            slot = slot, candidateId = null, value = q, normalized = normalized, page = pageOf(q), bbox = null,
            evidence = q, origin = SlotOrigin.MODEL_QUOTED, aiConfidence = ai, confidence = combined.final,
            validation = Validation.Unchecked, blocked = combined.blocked, notes = combined.notes,
            quoteMatch = match, role = role,
        )

        private fun nameValue(slot: SlotKey, ref: String?, aiWord: String?): SlotValue? {
            val r = ref?.trim()
            if (r.isNullOrEmpty() || r == StructuredGrammar.NONE) return null
            return resolveName(r, aiWord, expectedZones = null, label = slot.json)?.copy(slot = slot)
        }

        // ── validation and consistency checks ────────────────────────────────────

        /** The candidate's verdict, with dates re-checked against the model's letter date when code found none. */
        private fun effectiveValidation(c: Candidate, slot: SlotKey?, pastYears: Long = 1): Validation {
            if (c.kind != CandidateKind.DATE && c.kind != CandidateKind.DATETIME) return c.validation
            if (c.attrs["timeOnly"] != null || c.validation !is Validation.Unchecked) return c.validation
            val a = anchor ?: return c.validation
            val d = parseDate(c.normalized) ?: return c.validation
            if (slot?.canonical == Canonical.DOCUMENT_DATE && d == a) {
                return if (d.year in 2000..2100) Validation.Valid else Validation.Invalid("implausible letter date year ${d.year}")
            }
            return DateValidator.validate(d.year, d.monthValue, d.dayOfMonth, a, pastYears)
        }

        private fun validationCheck(v: Validation, kind: CandidateKind): Check = when {
            v is Validation.Invalid -> Check.Cap(Caps.INVALID, "failed a check: ${v.reason}")
            v is Validation.Unchecked && kind in setOf(CandidateKind.AMOUNT, CandidateKind.DATE, CandidateKind.DATETIME) ->
                Check.Cap(
                    Caps.UNCHECKED,
                    if (kind == CandidateKind.AMOUNT) "no currency next to the amount" else "date could not be range-checked",
                    blocking = false,
                )
            else -> Check.Pass
        }

        /** A value whose OCR character confusion was repaired is capped: what is stored is not exactly what was printed. */
        private fun repairCheck(c: Candidate): Check =
            if (c.attrs["repaired"] == null) {
                Check.Pass
            } else {
                Check.Cap(Caps.REPAIRED, "read with an OCR character repair (${c.raw.trim()} -> ${c.normalized})", blocking = false)
            }

        private fun roleCheck(slot: SlotKey, role: String?): Check {
            if (role == null || slot.expects.isEmpty() || role in slot.expects) return Check.Pass
            return Check.Cap(Caps.ROLE_MISMATCH, "the model calls this $role but put it in ${slot.json}")
        }

        private fun dateOrderCheck(slot: SlotKey, c: Candidate): Check {
            if (slot.kind != SlotKind.DEADLINE) return Check.Pass
            val letter = anchor ?: return Check.Pass
            val d = parseDate(c.normalized) ?: return Check.Pass
            if (!d.isBefore(letter)) return Check.Pass
            conflicts += "${slot.json} ($d) is before the letter date ($letter)"
            return Check.Cap(Caps.DATE_ORDER, "${slot.json} $d is before the letter date $letter")
        }

        /**
         * When the model puts the net or the VAT part of a net + VAT = gross triple in a slot that
         * should hold the payable total, it may have picked the wrong row. Arithmetic only.
         */
        private fun amountConsistencyCheck(slot: SlotKey, c: Candidate): Check {
            if (c.kind != CandidateKind.AMOUNT || "GROSS" !in slot.expects) return Check.Pass
            val cents = c.cents ?: return Check.Pass
            val amounts = ctx.offered.rows.map { it.candidate }
                .filter { it.kind == CandidateKind.AMOUNT && it.currency == c.currency }
                .mapNotNull { it.cents }.filter { it > 0 }.distinct()
            var isGross = false
            var isPart = false
            for (n in amounts) for (v in amounts) {
                if (n == v) continue
                for (g in amounts) {
                    if (g == n || g == v || !AmountConsistency.isNetVatGross(n, v, g)) continue
                    if (g == cents) isGross = true
                    if (cents == n || cents == v) isPart = true
                }
            }
            if (!isPart || isGross) return Check.Pass
            val msg = "${slot.json} ${c.raw} is the net or VAT part of a net + VAT = gross triple in the letter"
            conflicts += msg
            return Check.Cap(Caps.INCONSISTENT, msg)
        }

        private fun quoteCheck(match: QuoteMatch): Check = when (match) {
            QuoteMatch.EXACT -> Check.Cap(Caps.QUOTE_EXACT, "quoted text, not a found value", blocking = false)
            QuoteMatch.NORMALIZED -> Check.Cap(Caps.QUOTE_NORMALIZED, "quote matches after normalising", blocking = false)
            QuoteMatch.FUZZY -> Check.Cap(Caps.QUOTE_FUZZY, "quote only roughly matches the letter")
        }

        // ── parties ──────────────────────────────────────────────────────────────

        private val senderZones = setOf("LETTERHEAD", "RETURN_ADDRESS", "FOOTER")
        private val addresseeZones = setOf("ADDRESS_FIELD")
        private val addressSide = setOf(PartyRole.ADDRESSEE, PartyRole.CO_ADDRESSEE, PartyRole.ROUTING, PartyRole.CARE_OF)

        private fun verifyParties(): Parties {
            val parties = mutableListOf<Party>()
            for (rp in raw.parties) {
                val role = PartyRole.entries.firstOrNull { it.name == rp.role.trim().uppercase() }
                if (role == null) {
                    rejections += "party role '${rp.role}' is not one of ${PartyRole.entries.map { it.name }}"
                    continue
                }
                if (role == PartyRole.SENDER && parties.any { it.role == PartyRole.SENDER }) {
                    rejections += "a second SENDER '${rp.id.take(40)}' was ignored"
                    continue
                }
                val expected = when (role) {
                    PartyRole.SENDER -> senderZones
                    in addressSide -> addresseeZones
                    else -> null
                }
                val value = resolveName(rp.id.trim(), rp.confidence, expected, role.name) ?: continue
                val c = value.candidateId?.let { ctx.offered.get(it) }
                val kind = PartyKind.entries.firstOrNull { it.name == rp.kind?.trim()?.uppercase() }
                    ?: PartyKind.OTHER // the grammar always makes the model say it; code never guesses a kind
                val relation = PartyRelation.entries.firstOrNull { it.name == rp.relation?.trim()?.uppercase() } ?: PartyRelation.NONE
                val party = Party(role, kind, relation, value.copy(role = role.name))
                if (parties.none { it.role == party.role && sameParty(it, party) }) parties += party
            }
            return Parties(applyRoleConflicts(parties))
        }

        /** [ref] is a name candidate id, or a quoted name; the answer is dropped when it is neither. */
        private fun resolveName(ref: String, aiWord: String?, expectedZones: Set<String>?, label: String): SlotValue? {
            val c = ctx.offered.get(ref)
            if (c != null) {
                if (c.kind != CandidateKind.NAME) {
                    rejections += "$label: $ref is a ${c.kind}, not a name"
                    return null
                }
                usedCandidateIds += c.id
                val checks = mutableListOf<Check>()
                val zone = c.attrs["zone"]
                if (expectedZones != null && zone != null && zone !in expectedZones) {
                    val msg = "$label was taken from the ${zone.lowercase().replace('_', ' ')}, which contradicts the layout"
                    checks += Check.Cap(Caps.ZONE_MISMATCH, msg)
                    conflicts += "$label '${c.raw}': $msg"
                }
                if (c.attrs["guess"] != null) checks += Check.Cap(Caps.GUESS, "the name is a guess from its position", blocking = false)
                return build(null, c, aiWord, null, checks)
            }
            if (looksLikeId(ref)) {
                rejections += "$label: '$ref' is not an offered candidate"
                return null
            }
            val verified = QuoteVerifier.verify(ref, ctx.ocrText)
            if (verified == null) {
                rejections += "$label: the name '${ref.take(40)}' is not in the letter"
                return null
            }
            val ai = ConfidenceCombiner.aiScore(aiWord)
            return quotedSlotValue(null, ref, ai, ConfidenceCombiner.combine(ai, listOf(quoteCheck(verified.match))), verified.match)
        }

        /** Something like A3, D12, DT2 or NONE is meant as an id; a real name never looks like that. */
        private fun looksLikeId(s: String) = Regex("^[A-Z]{1,2}\\d{1,3}$").matches(s)

        private fun sameParty(a: Party, b: Party) =
            QuoteVerifier.fold(a.name).trim() == QuoteVerifier.fold(b.name).trim()

        /** The sender is never the addressee, a co-addressee, the routing person or the mailbox. */
        private fun applyRoleConflicts(parties: List<Party>): List<Party> {
            val sender = parties.firstOrNull { it.role == PartyRole.SENDER } ?: return parties
            val clash = parties.filter { p ->
                p.role in addressSide &&
                    (sameParty(p, sender) || (p.value.candidateId != null && p.value.candidateId == sender.value.candidateId))
            }
            if (clash.isEmpty()) return parties
            conflicts += "sender '${sender.name}' is also ${clash.joinToString { it.role.name }}"
            val flagged = clash.toSet() + sender
            return parties.map { p ->
                if (p in flagged) {
                    val combined = ConfidenceCombiner.combine(
                        p.value.confidence,
                        listOf(Check.Cap(Caps.CONFLICT, "the sender and the addressee are the same party")),
                    )
                    p.copy(value = p.value.copy(confidence = combined.final, blocked = true, notes = p.value.notes + combined.notes))
                } else {
                    p
                }
            }
        }

        // ── free text ────────────────────────────────────────────────────────────

        /** The text grammar has no confidence; MEDIUM by convention, and the quote tier caps it. */
        private val freeTextAi = ConfidenceCombiner.MEDIUM

        private fun verifyFreeText(t: RawText): FreeText {
            val subject = t.subject?.trim()?.takeIf { it.isNotEmpty() }?.let { quoted("subject", it) }
            val summary = t.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { verifySummary(it) }
            val title = t.title?.trim()?.takeIf { it.isNotEmpty() }?.let { generated(it, "title") }
            return FreeText(
                subject = subject,
                summary = summary,
                title = title,
                suggestedQuestions = t.questions.map { it.trim() }.filter { it.isNotEmpty() }.take(3),
            )
        }

        private fun quoted(label: String, text: String): SlotValue? {
            val verified = QuoteVerifier.verify(text, ctx.ocrText)
            if (verified == null) {
                rejections += "$label: '${text.take(40)}' is not in the letter"
                return null
            }
            return quotedSlotValue(null, text, freeTextAi, ConfidenceCombiner.combine(freeTextAi, listOf(quoteCheck(verified.match))), verified.match)
        }

        private fun generated(text: String, label: String): SlotValue {
            val combined = ConfidenceCombiner.combine(
                freeTextAi,
                listOf(Check.Cap(Caps.GENERATED, "written by the model ($label)", blocking = false)),
            )
            return SlotValue(
                slot = null, candidateId = null, value = text, normalized = text, page = null, bbox = null,
                evidence = "", origin = SlotOrigin.MODEL_GENERATED, aiConfidence = freeTextAi, confidence = combined.final,
                validation = Validation.Unchecked, notes = combined.notes,
            )
        }

        /** Each sentence must be in the letter for the summary to count as quoted; otherwise it is an AI summary. */
        private fun verifySummary(text: String): SlotValue {
            val sentences = text.split(Regex("(?<=[.!?؟。])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            val matches = sentences.map { QuoteVerifier.verify(it, ctx.ocrText) }
            if (sentences.isNotEmpty() && matches.all { it != null }) {
                val weakest = matches.filterNotNull().map { it.match }.maxBy { it.ordinal }
                val combined = ConfidenceCombiner.combine(freeTextAi, listOf(quoteCheck(weakest)))
                return quotedSlotValue(null, text, freeTextAi, combined, weakest).copy(page = pageOf(sentences.first()))
            }
            return generated(text, "AI summary").copy(notes = listOf("AI summary: not every sentence is in the letter"))
        }

        // ── extras ───────────────────────────────────────────────────────────────

        private val weakKinds = setOf(CandidateKind.PHONE, CandidateKind.EMAIL, CandidateKind.BIC)

        private fun verifyExtras(): List<ExtraValue> {
            val used = usedCandidateIds.toMutableSet()
            val out = mutableListOf<ExtraValue>()
            for (x in raw.extras) {
                if (out.size >= StructuredGrammar.MAX_EXTRAS) break
                val label = x.label.trim()
                if (label.isEmpty()) continue
                val value = extraValue(x, label, used) ?: continue
                val extra = ExtraValue(label, keyOf(x.key), value)
                if (out.any { it.identity == extra.identity || (it.value.normalized == value.normalized && it.key == extra.key) }) continue
                out += extra
            }
            return out
        }

        private fun extraValue(x: RawExtra, label: String, used: MutableSet<String>): SlotValue? {
            val id = x.id.trim()
            if (id.isNotEmpty() && id != StructuredGrammar.NONE) {
                val c = ctx.offered.get(id)
                if (c == null) {
                    rejections += "extra '$label': '$id' is not an offered candidate"
                    return null
                }
                if (id in used) {
                    rejections += "extra '$label': $id is already used by a field"
                    return null
                }
                if (c.kind in weakKinds && ConfidenceCombiner.aiScore(x.confidence) < ConfidenceCombiner.HIGH) {
                    rejections += "extra '$label': a ${c.kind} is only kept when the model is HIGH sure"
                    return null
                }
                used += id
                val checks = listOf(validationCheck(effectiveValidation(c, null, pastYears = 10), c.kind), repairCheck(c))
                return build(null, c, x.confidence, null, checks)
            }
            val q = x.value.trim()
            if (q.isEmpty()) {
                rejections += "extra '$label' has neither a candidate nor a quote"
                return null
            }
            val verified = QuoteVerifier.verify(q, ctx.ocrText)
            if (verified == null) {
                rejections += "extra '$label': '${q.take(40)}' is not in the letter"
                return null
            }
            val ai = ConfidenceCombiner.aiScore(x.confidence)
            return quotedSlotValue(null, q, ai, ConfidenceCombiner.combine(ai, listOf(quoteCheck(verified.match))), verified.match)
        }

        private fun keyOf(key: String): String {
            val k = key.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
            return if (Regex("[a-z][a-z0-9_]{1,29}").matches(k)) k else "other"
        }

        // ── helpers ──────────────────────────────────────────────────────────────

        private fun pageOf(text: String): Int? {
            for ((i, page) in ctx.pageTexts.withIndex()) if (QuoteVerifier.verify(text, page) != null) return i + 1
            return null
        }

        private fun parseDate(normalized: String): LocalDate? =
            runCatching { LocalDate.parse(normalized.take(10)) }.getOrNull()
    }
}
