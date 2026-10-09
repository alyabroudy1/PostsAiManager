package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.IbanValidator
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.text.DocumentNameVerifier
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import java.time.LocalDate

/** A party the model named and code accepted: [candidateId] of a name candidate, or the printed [quote] of a line of the letter. */
class VerifiedParty(
    val role: PartyRole,
    val kind: PartyKind,
    val candidateId: String?,
    val quote: String?,
    /** A name the "Questions" reader stored as the model wrote it: not looked for in the letter, the person confirms it ([QuestionReadingBuilder]). */
    val stated: Boolean = false,
) {
    /** What identifies the party for comparisons: its candidate id or its printed text. */
    val reference: String get() = candidateId ?: quote.orEmpty()
}

/** A date or an amount of the letter with the meaning the model gave it ([meaningId] null when it said "other"). */
class VerifiedValue(val candidate: Candidate, val meaningId: String?)

/** A reference, account or contact value with the kind the model gave it (a reference slot's key, `iban` or `other`). */
class VerifiedReference(val candidate: Candidate, val kind: String)

/** An action kind with the date and the amount (candidate ids of values that passed) the model said it is for. */
class VerifiedAction(val kind: String, val dateCandidateId: String?, val amountCandidateId: String?)

/**
 * What the model chose and a check failed, kept so that it is shown as a value "to check" with its [reason] instead of vanishing:
 * a [party] named by a line the letter's text does not hold, or a [value] (a date, an amount, a reference) of the letter the checks
 * refused (a date that does not exist, a due date before the letter's own, an IBAN with a wrong checksum).
 */
class ToCheck(val reason: String, val party: VerifiedParty? = null, val value: Candidate? = null, val meaningId: String? = null)

/**
 * What survived the checks. Everything a check failed is not here and is listed in [drops] (a reason, never a word of the letter), so a
 * field the model got wrong never shows as a sure one, and the trial's log can say why. What the model chose but a check refused is also
 * in [toCheck], to be shown as a value that needs a look, and the reasons of everything lost are in [losses] (the reading needs review).
 */
class VerifiedReading(
    val parties: List<VerifiedParty>,
    val dates: List<VerifiedValue>,
    val amounts: List<VerifiedValue>,
    val references: List<VerifiedReference>,
    val actions: List<VerifiedAction>,
    val category: String,
    val language: String?,
    val name: String?,
    val drops: List<String>,
    /** What the letter reports on the timeline: a kind of the event registry, or null when the model named none the registry knows. */
    val eventKind: String? = null,
    /** The model's answer to "does the letter ask its reader to do anything?"; null when it gave none. */
    val asksReader: Boolean? = null,
    /** The model's answer to "has it been paid already?"; null when it gave none. The summary step is told it. */
    val paid: PaidState? = null,
    /** The reasons of the answers that were lost (not those that only became "other"): the reading needs a look. */
    val losses: List<String> = emptyList(),
    /** What the model chose that a check refused, as values to check. */
    val toCheck: List<ToCheck> = emptyList(),
    /**
     * Values the "Questions" reader typed from the model's words that no candidate of the letter matched (ids "Q1", "Q2" ...): added to
     * the candidates the result is built from, so they are stored like any other, to be confirmed by the person.
     */
    val synthesized: List<Candidate> = emptyList(),
    /** What the "Questions" reader did with the answers (how a name was stored, how many items): trace lines that are not drops, as nothing is dropped. */
    val notes: List<String> = emptyList(),
)

/** The reasons a reading collects: every one goes to the trace, the ones that [lose] an answer also make the reading need review. */
internal class Drops {
    val all = mutableListOf<String>()
    val lost = mutableListOf<String>()

    /** An answer that did not survive. */
    fun lose(reason: String) {
        all += reason
        lost += reason
    }

    /** A correction that keeps the value (a meaning that became "other", an action without its date). */
    fun adjust(reason: String) {
        all += reason
    }

    operator fun plusAssign(reason: String) = lose(reason)
}

/**
 * "Code verifies": decides which of the model's answers may be kept. It never picks, repairs or words anything itself; an answer that
 * fails a check is not kept as it was said, and what the model did choose is shown as a value to check ([ToCheck]).
 *
 * This is the one verifier of a Gemma reading: the result is mapped from it straight to the stored understanding
 * ([GemmaResultVerifier]), so the model's decision and these checks are all that set a value's confidence.
 *
 * - **Ids exist.** Every candidate or line id is one of this letter's, of the right kind (a date answer is a date candidate ...).
 * - **Parties.** A line chosen as a name is quoted from the letter and must be found in its text; the sender is never the addressee
 *   (the addressee is dropped) and the contact is never the addressee.
 * - **Dates** are real calendar dates the candidate's own validation accepts; a due date is not before the letter's date (when both
 *   are known, the letter's own date being the one the model named LETTER_DATE, else the one code found).
 * - **Amounts** parse to money and pass their candidate's validation. The one the reader has to pay ([GemmaReading.toPayId]) is its own
 *   answer: it gets the meaning of the amount to pay, whatever the list says of it.
 * - **Accounts** are IBAN candidates whose checksum is right, and only they; a contact value (phone, e-mail, BIC) is only "other".
 * - **Actions** keep their kind (a registry id); a date or an amount they point at is kept only when it was kept above.
 * - **Paid.** The answers agree with the model's own "paid" ([PaidConsistency]): a document it says is already paid has no pay action,
 *   no "amount to pay" and no "pay by" date.
 * - **The name** is grounded in the letter with [DocumentNameVerifier]. (The summary and the key facts are not part of this answer; the
 *   second step writes them and checks them with their own verifiers, see [GemmaTextWriter].)
 */
class GemmaReadingVerifier(
    private val vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT,
    private val names: DocumentNameVerifier = DocumentNameVerifier(),
) {

    /**
     * @param ocrText the letter's text, the grounding reference of every quote and free text
     * @param letterDate the letter's date as code found it (the checks of a due date use it when the model named none)
     */
    fun verify(reading: GemmaReading, letter: GemmaLetter, offered: OfferedCandidates, ocrText: String, letterDate: LocalDate?): VerifiedReading {
        val drops = Drops()
        val toCheck = mutableListOf<ToCheck>()
        val parties = parties(reading, letter, offered, ocrText, drops, toCheck)
        val dates = dates(reading, letter, offered, letterDate, drops, toCheck)
        val amounts = amounts(reading, letter, offered, drops, toCheck)
        val references = references(reading, letter, offered, drops, toCheck)
        // The model's own answers must agree: a letter it says asks nothing of its reader has no action (it invented them).
        val actions = if (reading.asksReader == false) {
            if (reading.actions.isNotEmpty()) drops.adjust("${reading.actions.size} action(s) dropped: the model said the letter asks nothing of its reader")
            emptyList()
        } else {
            actions(reading, dates, amounts, drops)
        }
        val eventKind = vocab.eventKind(reading.eventKind)
            .also { if (reading.eventKind != null && it == null) drops.adjust("event kind '${reading.eventKind}' is not in the registry") }

        val category = reading.category?.trim()?.lowercase()?.takeIf { it in vocab.categoryIds }
            ?: GemmaVocabulary.DOCUMENT_CATEGORY.also { if (reading.category != null) drops += "category '${reading.category}' is not in the registry" }

        val language = reading.language?.trim()?.lowercase()?.takeIf { GemmaSchema.LANGUAGE_CODE.matches(it) }
            .also { if (reading.language != null && it == null) drops.adjust("the language is not a language code") }

        val name = reading.name?.takeIf { it.isNotBlank() }?.let {
            names.verify(it, ocrText).also { kept -> if (kept == null) drops.adjust("the document name is not grounded in the letter") }
        }

        return VerifiedReading(
            parties, dates, amounts, references, actions, category, language, name, drops.all, eventKind, reading.asksReader, reading.paid,
            losses = drops.lost, toCheck = toCheck,
        )
    }

    // ── parties ──

    private fun parties(
        reading: GemmaReading, letter: GemmaLetter, offered: OfferedCandidates, ocrText: String, drops: Drops, toCheck: MutableList<ToCheck>,
    ): List<VerifiedParty> {
        fun resolve(role: PartyRole, p: GemmaParty?): VerifiedParty? {
            val id = p?.id?.trim()?.takeIf { it.isNotEmpty() && !it.equals(GemmaVocabulary.NONE, ignoreCase = true) } ?: return null
            val kind = PartyKind.entries.firstOrNull { it.name.equals(p.kind?.trim(), ignoreCase = true) } ?: PartyKind.OTHER
            val candidate = letter.candidate(id)
            if (candidate != null) {
                if (candidate.kind != CandidateKind.NAME) return null.also { drops += "${role.name}: $id is a ${candidate.kind}, not a name" }
                return VerifiedParty(role, kind, candidate.id, null)
            }
            val chosen = letter.line(id) ?: return null.also { drops += "${role.name}: '$id' is neither a candidate nor a line of the letter" }
            // The label of a label/value pair is no party: the party is what the same block pairs with it (its value line, as a name
            // candidate, or as the line itself); with no value line the party is dropped.
            val line = if (chosen.isLabel) {
                val value = chosen.valueLineId?.let(letter::line)
                    ?: return null.also { drops += "${role.name}: line $id is a label with no value line" }
                val named = letter.candidatesOf(CandidateKind.NAME).firstOrNull { it.lineId == value.id }
                if (named != null) {
                    drops.adjust("${role.name}: line $id is a label; its value ${named.id} was taken")
                    return VerifiedParty(role, kind, named.id, null)
                }
                drops.adjust("${role.name}: line $id is a label; its value line ${value.id} was taken")
                value
            } else {
                chosen
            }
            val text = line.text.trim()
            // A line that is a name candidate's whole text is that candidate (linking, contacts and suggestions work from candidates).
            letter.candidatesOf(CandidateKind.NAME).firstOrNull { it.lineId == line.id && it.raw.trim() == text }?.let { named ->
                drops.adjust("${role.name}: line ${line.id} is the name candidate ${named.id}, which was taken")
                return VerifiedParty(role, kind, named.id, null)
            }
            val party = VerifiedParty(role, kind, null, text)
            if (QuoteVerifier.verify(text, ocrText) == null) {
                val reason = "${role.name}: line $id is not found in the letter's text"
                drops += reason
                toCheck += ToCheck(reason, party = party)
                return null
            }
            return party
        }

        var sender = resolve(PartyRole.SENDER, reading.sender)
        var addressee = resolve(PartyRole.ADDRESSEE, reading.addressee)
        var contact = resolve(PartyRole.CONTACT, reading.contact)
        val subject = resolve(PartyRole.SUBJECT_PERSON, reading.subjectPerson)
        // Layout consistency: the sender stands in the address field (the addressee's place) while the addressee does not.
        if (sender != null && addressee != null && zoneOf(sender, letter) == ADDRESS_FIELD && zoneOf(addressee, letter) != ADDRESS_FIELD) {
            if (zoneOf(addressee, letter) in SENDER_ZONES) {
                drops.adjust("SENDER and ADDRESSEE swapped: the sender was printed in the address field, the addressee in the letterhead")
                val swapped = addressee.withRole(PartyRole.SENDER) to sender.withRole(PartyRole.ADDRESSEE)
                sender = swapped.first
                addressee = swapped.second
            } else {
                val reason = "SENDER and ADDRESSEE contradict the layout (the sender is in the address field, the addressee is not)"
                drops += reason
                toCheck += ToCheck(reason, party = sender.asQuote(letter))
                toCheck += ToCheck(reason, party = addressee.asQuote(letter))
                sender = null
                addressee = null
            }
        }
        if (sender != null && addressee != null && same(sender, addressee, offered)) {
            drops += "ADDRESSEE: the same party as the sender"
            addressee = null
        }
        if (contact != null && addressee != null && same(contact, addressee, offered)) {
            drops += "CONTACT: the same party as the addressee"
            contact = null
        }
        return listOfNotNull(sender, addressee, contact, subject)
    }

    /** The layout zone (a [LetterZone] tag) of the line [p] is printed on: its candidate's line, or the line it quotes; null when unknown. */
    private fun zoneOf(p: VerifiedParty, letter: GemmaLetter): String? {
        val line = p.candidateId?.let { id -> letter.candidate(id)?.lineId?.let(letter::line) }
            ?: p.quote?.let { q -> letter.lines.firstOrNull { it.text.trim() == q } }
        return line?.zone
    }

    /** [p] as the line of the letter it was printed as (a candidate becomes its printed text), the form a value to check is shown in. */
    private fun VerifiedParty.asQuote(letter: GemmaLetter): VerifiedParty =
        VerifiedParty(role, kind, null, candidateId?.let { letter.candidate(it)?.raw?.trim() } ?: quote)

    private fun VerifiedParty.withRole(role: PartyRole) = VerifiedParty(role, kind, candidateId, quote)

    private fun same(a: VerifiedParty, b: VerifiedParty, offered: OfferedCandidates): Boolean {
        if (a.candidateId != null && a.candidateId == b.candidateId) return true
        fun text(p: VerifiedParty) = QuoteVerifier.fold(p.candidateId?.let { offered.get(it)?.raw } ?: p.quote.orEmpty()).trim()
        return text(a).isNotEmpty() && text(a) == text(b)
    }

    // ── dates and amounts ──

    private fun dates(
        reading: GemmaReading, letter: GemmaLetter, offered: OfferedCandidates, letterDate: LocalDate?, drops: Drops, toCheck: MutableList<ToCheck>,
    ): List<VerifiedValue> {
        val kept = LinkedHashMap<String, VerifiedValue>()
        for (v in reading.dates) {
            val c = candidate(v.candidateId, letter, offered, "date", drops, CandidateKind.DATE, CandidateKind.DATETIME) ?: continue
            if (c.validation.isInvalid) {
                refuse(c, vocab.dateMeaning(v.meaning)?.id, "date ${c.id}: failed its own check", drops, toCheck)
                continue
            }
            if (parse(c) == null) {
                refuse(c, vocab.dateMeaning(v.meaning)?.id, "date ${c.id}: does not parse as a calendar date", drops, toCheck)
                continue
            }
            val meaning = vocab.dateMeaning(v.meaning)?.id
            val consistent = PaidConsistency.dateMeaning(reading.paid, meaning)
            if (consistent != meaning) drops.adjust("date ${c.id}: ${meaning} is no meaning for a document the model said is already paid: kept as other")
            kept.putIfAbsent(c.id, VerifiedValue(c, consistent))
        }
        // A due date does not come before the date of the letter: the letter's own date as the model named it, else the one code found.
        val letterOn = kept.values.firstOrNull { it.meaningId == LETTER_DATE }?.let { parse(it.candidate) } ?: letterDate
        if (letterOn != null) {
            kept.values.filter { it.meaningId == DUE_DATE }.toList().forEach { due ->
                if (parse(due.candidate)!!.isBefore(letterOn)) {
                    refuse(due.candidate, DUE_DATE, "due date ${due.candidate.id}: before the letter's date", drops, toCheck)
                    kept.remove(due.candidate.id)
                }
            }
        }
        return singleOwners(kept.values.toList(), drops)
    }

    private fun amounts(
        reading: GemmaReading, letter: GemmaLetter, offered: OfferedCandidates, drops: Drops, toCheck: MutableList<ToCheck>,
    ): List<VerifiedValue> {
        val kept = LinkedHashMap<String, VerifiedValue>()
        // The amount to pay is its own answer and comes first: the amount list cannot take its meaning from it.
        val answers = listOfNotNull(reading.toPayId?.let { GemmaValue(candidateId = it, meaning = GemmaVocabulary.TO_PAY_MEANING) }) + reading.amounts
        for (v in answers) {
            val c = candidate(v.candidateId, letter, offered, "amount", drops, CandidateKind.AMOUNT) ?: continue
            val meaning = vocab.amountMeaning(v.meaning)?.id
            if (c.cents == null) {
                refuse(c, meaning, "amount ${c.id}: does not parse as money", drops, toCheck)
                continue
            }
            if (c.validation.isInvalid) {
                refuse(c, meaning, "amount ${c.id}: failed its own check", drops, toCheck)
                continue
            }
            val consistent = PaidConsistency.amountMeaning(reading.paid, meaning)
            if (consistent != meaning) drops.adjust("amount ${c.id}: $meaning became $consistent for a document the model said is already paid")
            kept.putIfAbsent(c.id, VerifiedValue(c, consistent))
        }
        return singleOwners(kept.values.toList(), drops)
    }

    /** A value of the letter the model chose and a check refused: lost as it was said, kept as a value to check. */
    private fun refuse(c: Candidate, meaningId: String?, reason: String, drops: Drops, toCheck: MutableList<ToCheck>) {
        drops += reason
        toCheck += ToCheck(reason, value = c, meaningId = meaningId)
    }

    /**
     * A meaning only one value of a document can have ("the amount to pay", "the date of the letter", see [com.postsaimanager.core.domain.extraction.v2.ValueMeaning.exclusive])
     * stays with the value the model listed first; a later value that claims it too is one the model got wrong, and is kept as "other"
     * (the value itself is a real candidate, only the meaning goes). A meaning the model gave "other" is already no meaning.
     */
    private fun singleOwners(values: List<VerifiedValue>, drops: Drops): List<VerifiedValue> {
        val taken = HashSet<String>()
        return values.map { v ->
            val id = v.meaningId ?: return@map v
            val exclusive = vocab.meanings.byId(id)?.exclusive == true
            if (!exclusive || taken.add(id)) {
                v
            } else {
                drops.adjust("$id was claimed by ${v.candidate.id} as well: kept as other, the value listed first keeps it")
                VerifiedValue(v.candidate, null)
            }
        }
    }

    // ── references, accounts, contact values ──

    private fun references(
        reading: GemmaReading, letter: GemmaLetter, offered: OfferedCandidates, drops: Drops, toCheck: MutableList<ToCheck>,
    ): List<VerifiedReference> {
        val kept = LinkedHashMap<String, VerifiedReference>()
        for (v in reading.references) {
            val c = candidate(
                v.candidateId, letter, offered, "reference", drops,
                CandidateKind.REFERENCE, CandidateKind.IBAN, CandidateKind.PHONE, CandidateKind.EMAIL, CandidateKind.BIC,
            ) ?: continue
            val kind = v.meaning?.trim()?.takeIf { it in vocab.referenceKinds } ?: GemmaVocabulary.OTHER
            val ok = when (c.kind) {
                CandidateKind.IBAN -> kind == GemmaVocabulary.IBAN_KIND && c.validation.isValid && IbanValidator.hasValidChecksum(IbanValidator.compact(c.normalized))
                CandidateKind.REFERENCE -> kind != GemmaVocabulary.IBAN_KIND && !c.validation.isInvalid
                else -> kind == GemmaVocabulary.OTHER && !c.validation.isInvalid
            }
            if (!ok) {
                refuse(c, null, "reference ${c.id}: a ${c.kind} cannot be '$kind' or failed its check", drops, toCheck)
                continue
            }
            kept.putIfAbsent(c.id, VerifiedReference(c, kind))
        }
        return kept.values.toList()
    }

    // ── actions ──

    private fun actions(reading: GemmaReading, dates: List<VerifiedValue>, amounts: List<VerifiedValue>, drops: Drops): List<VerifiedAction> {
        val out = LinkedHashMap<String, VerifiedAction>()
        for (a in reading.actions) {
            val kind = vocab.actionKind(a.kind)?.id ?: run {
                drops.adjust("action '${a.kind}' is not in the registry")
                null
            } ?: continue
            if (!PaidConsistency.keepsAction(reading.paid, kind)) {
                drops.adjust("action $kind dropped: the model said everything is already paid")
                continue
            }
            val kept = a.dateId?.let { id -> dates.firstOrNull { it.candidate.id == id } }
            // The model's own answers must agree: a date it called the letter's date, a period or "other" is not a deadline.
            val meansDeadline = kept == null || kept.meaningId in ACTION_DATE_MEANINGS
            if (kept != null && !meansDeadline) drops.adjust("action $kind: date ${kept.candidate.id} was given another meaning, not a deadline")
            val date = kept?.takeIf { meansDeadline }?.candidate?.id
            val amount = a.amountId?.takeIf { id -> amounts.any { it.candidate.id == id } }
            if (kept != null && !meansDeadline && amount == null && vocab.actionKind(kind)?.let { it.dateMeaning != null || it.amountMeaning != null } == true) {
                drops.adjust("action $kind: nothing is left of it (no date, no amount)")
                continue
            }
            if (a.dateId != null && date == null && !a.dateId.equals(GemmaVocabulary.NONE, true)) drops.adjust("action $kind: date ${a.dateId} was not kept")
            if (a.amountId != null && amount == null && !a.amountId.equals(GemmaVocabulary.NONE, true)) drops.adjust("action $kind: amount ${a.amountId} was not kept")
            out.putIfAbsent(kind, VerifiedAction(kind, date, amount))
        }
        return out.values.toList()
    }

    // ── helpers ──

    /** The candidate [id] names, when it is one of this letter's offered candidates of one of [kinds]; else null and the reason is logged. */
    private fun candidate(id: String?, letter: GemmaLetter, offered: OfferedCandidates, what: String, drops: Drops, vararg kinds: CandidateKind): Candidate? {
        val key = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null.also { drops += "$what without an id" }
        val listed = letter.candidate(key)
        val c = offered.get(key)
        if (listed == null || c == null) return null.also { drops += "$what '$key' is not a candidate of this letter" }
        if (c.kind !in kinds) return null.also { drops += "$what $key is a ${c.kind}" }
        return c
    }

    private fun parse(c: Candidate): LocalDate? = runCatching { LocalDate.parse(c.normalized.take(DATE_CHARS)) }.getOrNull()

    private companion object {
        val ADDRESS_FIELD = LetterZone.ADDRESS_FIELD.tag

        /** The zones that name the sender: a name printed there is the letter's sender, not its addressee. */
        val SENDER_ZONES = setOf(LetterZone.LETTERHEAD.tag, LetterZone.RETURN_ADDRESS_LINE.tag)
        const val LETTER_DATE = "LETTER_DATE"
        const val DUE_DATE = "DUE_DATE"

        /** The date meanings (registry ids) that can be what an action is due by or happens at; any other meaning is not a deadline. */
        val ACTION_DATE_MEANINGS = setOf(DUE_DATE, "DEADLINE", "APPOINTMENT")
        const val DATE_CHARS = 10
    }
}
