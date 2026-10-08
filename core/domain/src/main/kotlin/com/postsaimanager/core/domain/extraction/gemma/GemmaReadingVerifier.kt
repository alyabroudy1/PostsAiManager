package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.IbanValidator
import com.postsaimanager.core.domain.extraction.text.DocumentNameVerifier
import com.postsaimanager.core.domain.extraction.text.KeyInfoFormat
import com.postsaimanager.core.domain.extraction.text.KeyInfoVerifier
import com.postsaimanager.core.domain.extraction.text.SummaryGate
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import java.time.LocalDate

/** A party the model named and code accepted: [candidateId] of a name candidate, or the printed [quote] of a line of the letter. */
class VerifiedParty(val role: PartyRole, val kind: PartyKind, val candidateId: String?, val quote: String?) {
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
 * What survived the checks. Everything a check failed is not here and is listed in [drops] (a reason, never a word of the letter), so a
 * field the model got wrong stays empty instead of showing a wrong value, and the trial's log can say why.
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
    val summary: String?,
    val keyInfo: List<KeyInfoVerifier.Kept>,
    val drops: List<String>,
    /** What the letter reports on the timeline: a kind of the event registry, or null when the model named none the registry knows. */
    val eventKind: String? = null,
    /** The model's answer to "does the letter ask its reader to do anything?"; null when it gave none. */
    val asksReader: Boolean? = null,
)

/**
 * "Code verifies": decides which of the model's answers may be kept. It never picks, repairs or words anything itself; an answer that
 * fails a check is dropped whole and the field stays empty.
 *
 * - **Ids exist.** Every candidate or line id is one of this letter's, of the right kind (a date answer is a date candidate ...).
 * - **Parties.** A line chosen as a name is quoted from the letter and must be found in its text; the sender is never the addressee
 *   (the addressee is dropped) and the contact is never the addressee.
 * - **Dates** are real calendar dates the candidate's own validation accepts; a due date is not before the letter's date (when both
 *   are known, the letter's own date being the one the model named LETTER_DATE, else the one code found).
 * - **Amounts** parse to money and pass their candidate's validation.
 * - **Accounts** are IBAN candidates whose checksum is right, and only they; a contact value (phone, e-mail, BIC) is only "other".
 * - **Actions** keep their kind (a registry id); a date or an amount they point at is kept only when it was kept above.
 * - **Free texts** are grounded in the letter with the verifiers every other text passes: [DocumentNameVerifier] for the name,
 *   [SummaryGate] for the summary (numbers and names must be in the letter, no copied line), [KeyInfoVerifier] for the key facts.
 */
class GemmaReadingVerifier(
    private val vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT,
    private val names: DocumentNameVerifier = DocumentNameVerifier(),
    private val summaries: SummaryGate = SummaryGate(),
    private val keyInfos: KeyInfoVerifier = KeyInfoVerifier(),
) {

    /**
     * @param ocrText the letter's text, the grounding reference of every quote and free text
     * @param letterDate the letter's date as code found it (the checks of a due date use it when the model named none)
     */
    fun verify(reading: GemmaReading, letter: GemmaLetter, offered: OfferedCandidates, ocrText: String, letterDate: LocalDate?): VerifiedReading {
        val drops = mutableListOf<String>()
        val parties = parties(reading, letter, offered, ocrText, drops)
        val dates = dates(reading, letter, offered, letterDate, drops)
        val amounts = amounts(reading, letter, offered, drops)
        val references = references(reading, letter, offered, drops)
        // The model's own answers must agree: a letter it says asks nothing of its reader has no action (it invented them).
        val actions = if (reading.asksReader == false) {
            if (reading.actions.isNotEmpty()) drops += "${reading.actions.size} action(s) dropped: the model said the letter asks nothing of its reader"
            emptyList()
        } else {
            actions(reading, dates, amounts, drops)
        }
        val eventKind = vocab.eventKind(reading.eventKind)
            .also { if (reading.eventKind != null && it == null) drops += "event kind '${reading.eventKind}' is not in the registry" }

        val category = reading.category?.trim()?.lowercase()?.takeIf { it in vocab.categoryIds }
            ?: GemmaVocabulary.DOCUMENT_CATEGORY.also { if (reading.category != null) drops += "category '${reading.category}' is not in the registry" }

        val language = reading.language?.trim()?.lowercase()?.takeIf { GemmaSchema.LANGUAGE_CODE.matches(it) }
            .also { if (reading.language != null && it == null) drops += "the language is not a language code" }

        val name = reading.name?.takeIf { it.isNotBlank() }?.let {
            names.verify(it, ocrText).also { kept -> if (kept == null) drops += "the document name is not grounded in the letter" }
        }

        val known = parties.mapNotNull { p -> p.candidateId?.let { offered.get(it)?.raw } ?: p.quote } +
            dates.map { it.candidate.raw } + amounts.map { it.candidate.raw } + references.map { it.candidate.raw }
        val summary = reading.summary?.takeIf { it.isNotBlank() }?.let { text ->
            when (val verdict = summaries.check(text, ocrText, known)) {
                is SummaryGate.Verdict.Accepted -> verdict.text
                is SummaryGate.Verdict.Rejected -> null.also { drops += "the summary was rejected: ${verdict.reason}" }
            }
        }

        val facts = reading.keyInfo.map { KeyInfoFormat.Fact(it.label, it.value) }
        val keyInfo = keyInfos.verify(facts, ocrText, known).also {
            if (it.size < facts.size) drops += "${facts.size - it.size} key fact(s) dropped: not in the letter, a repeat of a read value, or over the limit"
        }
        return VerifiedReading(parties, dates, amounts, references, actions, category, language, name, summary, keyInfo, drops, eventKind, reading.asksReader)
    }

    // ── parties ──

    private fun parties(reading: GemmaReading, letter: GemmaLetter, offered: OfferedCandidates, ocrText: String, drops: MutableList<String>): List<VerifiedParty> {
        fun resolve(role: PartyRole, p: GemmaParty?): VerifiedParty? {
            val id = p?.id?.trim()?.takeIf { it.isNotEmpty() && !it.equals(GemmaVocabulary.NONE, ignoreCase = true) } ?: return null
            val kind = PartyKind.entries.firstOrNull { it.name.equals(p.kind?.trim(), ignoreCase = true) } ?: PartyKind.OTHER
            val candidate = letter.candidate(id)
            if (candidate != null) {
                if (candidate.kind != CandidateKind.NAME) return null.also { drops += "${role.name}: $id is a ${candidate.kind}, not a name" }
                return VerifiedParty(role, kind, candidate.id, null)
            }
            val line = letter.line(id) ?: return null.also { drops += "${role.name}: '$id' is neither a candidate nor a line of the letter" }
            val text = line.text.trim()
            if (QuoteVerifier.verify(text, ocrText) == null) return null.also { drops += "${role.name}: line $id is not found in the letter's text" }
            return VerifiedParty(role, kind, null, text)
        }

        val sender = resolve(PartyRole.SENDER, reading.sender)
        var addressee = resolve(PartyRole.ADDRESSEE, reading.addressee)
        var contact = resolve(PartyRole.CONTACT, reading.contact)
        val subject = resolve(PartyRole.SUBJECT_PERSON, reading.subjectPerson)
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

    private fun same(a: VerifiedParty, b: VerifiedParty, offered: OfferedCandidates): Boolean {
        if (a.candidateId != null && a.candidateId == b.candidateId) return true
        fun text(p: VerifiedParty) = QuoteVerifier.fold(p.candidateId?.let { offered.get(it)?.raw } ?: p.quote.orEmpty()).trim()
        return text(a).isNotEmpty() && text(a) == text(b)
    }

    // ── dates and amounts ──

    private fun dates(reading: GemmaReading, letter: GemmaLetter, offered: OfferedCandidates, letterDate: LocalDate?, drops: MutableList<String>): List<VerifiedValue> {
        val kept = LinkedHashMap<String, VerifiedValue>()
        for (v in reading.dates) {
            val c = candidate(v.candidateId, letter, offered, "date", drops, CandidateKind.DATE, CandidateKind.DATETIME) ?: continue
            if (c.validation.isInvalid) {
                drops += "date ${c.id}: failed its own check"
                continue
            }
            if (parse(c) == null) {
                drops += "date ${c.id}: does not parse as a calendar date"
                continue
            }
            kept.putIfAbsent(c.id, VerifiedValue(c, vocab.dateMeaning(v.meaning)?.id))
        }
        // A due date does not come before the date of the letter: the letter's own date as the model named it, else the one code found.
        val letterOn = kept.values.firstOrNull { it.meaningId == LETTER_DATE }?.let { parse(it.candidate) } ?: letterDate
        if (letterOn != null) {
            kept.values.filter { it.meaningId == DUE_DATE }.toList().forEach { due ->
                if (parse(due.candidate)!!.isBefore(letterOn)) {
                    drops += "due date ${due.candidate.id}: before the letter's date"
                    kept.remove(due.candidate.id)
                }
            }
        }
        return singleOwners(kept.values.toList(), drops)
    }

    private fun amounts(reading: GemmaReading, letter: GemmaLetter, offered: OfferedCandidates, drops: MutableList<String>): List<VerifiedValue> {
        val kept = LinkedHashMap<String, VerifiedValue>()
        for (v in reading.amounts) {
            val c = candidate(v.candidateId, letter, offered, "amount", drops, CandidateKind.AMOUNT) ?: continue
            if (c.cents == null) {
                drops += "amount ${c.id}: does not parse as money"
                continue
            }
            if (c.validation.isInvalid) {
                drops += "amount ${c.id}: failed its own check"
                continue
            }
            kept.putIfAbsent(c.id, VerifiedValue(c, vocab.amountMeaning(v.meaning)?.id))
        }
        return singleOwners(kept.values.toList(), drops)
    }

    /**
     * A meaning only one value of a document can have ("the amount to pay", "the date of the letter", see [com.postsaimanager.core.domain.extraction.v2.ValueMeaning.exclusive])
     * stays with the value the model listed first; a later value that claims it too is one the model got wrong, and is kept as "other"
     * (the value itself is a real candidate, only the meaning goes). A meaning the model gave "other" is already no meaning.
     */
    private fun singleOwners(values: List<VerifiedValue>, drops: MutableList<String>): List<VerifiedValue> {
        val taken = HashSet<String>()
        return values.map { v ->
            val id = v.meaningId ?: return@map v
            val exclusive = vocab.meanings.byId(id)?.exclusive == true
            if (!exclusive || taken.add(id)) {
                v
            } else {
                drops += "$id was claimed by ${v.candidate.id} as well: kept as other, the value listed first keeps it"
                VerifiedValue(v.candidate, null)
            }
        }
    }

    // ── references, accounts, contact values ──

    private fun references(reading: GemmaReading, letter: GemmaLetter, offered: OfferedCandidates, drops: MutableList<String>): List<VerifiedReference> {
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
                drops += "reference ${c.id}: a ${c.kind} cannot be '$kind' or failed its check"
                continue
            }
            kept.putIfAbsent(c.id, VerifiedReference(c, kind))
        }
        return kept.values.toList()
    }

    // ── actions ──

    private fun actions(reading: GemmaReading, dates: List<VerifiedValue>, amounts: List<VerifiedValue>, drops: MutableList<String>): List<VerifiedAction> {
        val out = LinkedHashMap<String, VerifiedAction>()
        for (a in reading.actions) {
            val kind = vocab.actionKind(a.kind)?.id ?: run {
                drops += "action '${a.kind}' is not in the registry"
                null
            } ?: continue
            val kept = a.dateId?.let { id -> dates.firstOrNull { it.candidate.id == id } }
            // The model's own answers must agree: a date it called the letter's date, a period or "other" is not a deadline.
            val meansDeadline = kept == null || kept.meaningId in ACTION_DATE_MEANINGS
            if (kept != null && !meansDeadline) drops += "action $kind: date ${kept.candidate.id} was given another meaning, not a deadline"
            val date = kept?.takeIf { meansDeadline }?.candidate?.id
            val amount = a.amountId?.takeIf { id -> amounts.any { it.candidate.id == id } }
            if (kept != null && !meansDeadline && amount == null && vocab.actionKind(kind)?.let { it.dateMeaning != null || it.amountMeaning != null } == true) {
                drops += "action $kind: nothing is left of it (no date, no amount)"
                continue
            }
            if (a.dateId != null && date == null && !a.dateId.equals(GemmaVocabulary.NONE, true)) drops += "action $kind: date ${a.dateId} was not kept"
            if (a.amountId != null && amount == null && !a.amountId.equals(GemmaVocabulary.NONE, true)) drops += "action $kind: amount ${a.amountId} was not kept"
            out.putIfAbsent(kind, VerifiedAction(kind, date, amount))
        }
        return out.values.toList()
    }

    // ── helpers ──

    /** The candidate [id] names, when it is one of this letter's offered candidates of one of [kinds]; else null and the reason is logged. */
    private fun candidate(id: String?, letter: GemmaLetter, offered: OfferedCandidates, what: String, drops: MutableList<String>, vararg kinds: CandidateKind): Candidate? {
        val key = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null.also { drops += "$what without an id" }
        val listed = letter.candidate(key)
        val c = offered.get(key)
        if (listed == null || c == null) return null.also { drops += "$what '$key' is not a candidate of this letter" }
        if (c.kind !in kinds) return null.also { drops += "$what $key is a ${c.kind}" }
        return c
    }

    private fun parse(c: Candidate): LocalDate? = runCatching { LocalDate.parse(c.normalized.take(DATE_CHARS)) }.getOrNull()

    private companion object {
        const val LETTER_DATE = "LETTER_DATE"
        const val DUE_DATE = "DUE_DATE"

        /** The date meanings (registry ids) that can be what an action is due by or happens at; any other meaning is not a deadline. */
        val ACTION_DATE_MEANINGS = setOf(DUE_DATE, "DEADLINE", "APPOINTMENT")
        const val DATE_CHARS = 10
    }
}
