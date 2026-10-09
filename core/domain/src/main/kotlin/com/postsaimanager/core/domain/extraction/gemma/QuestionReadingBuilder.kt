package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.text.DocumentNameFormat
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier

/**
 * Maps the "Questions" reader's answers ([QaAnswers]) onto [VerifiedReading], the type the rest of the reading (the mapper, the result,
 * the contacts, the follow-ups, the timeline, the UI) already takes, so none of it changes.
 *
 * The answers are stored as the model gave them; the person confirms them in the app as always. So nothing is looked for in the OCR
 * text and nothing is dropped for not being there. What this does is only what storing needs:
 *
 * - a **name** is linked to the letter's name candidate that holds it (so contacts and profiles link as they do for a candidate), else
 *   stored as the text the model wrote ([VerifiedParty.stated]);
 * - a **date** or an **amount** is typed ([QaValueTyper]): the letter's own candidate when it holds the same value, else a new one;
 * - a **reference** is the letter's candidate with the same characters, else a new one; the contact's phone and e-mail are references;
 * - a word from a registry list (a meaning, an action kind, a category, a kind of reference, a paid state, an event kind) is taken as that
 *   registry's id when it is one, else it is "other" (a meaning, a kind) or the registry's neutral entry (the category).
 */
class QuestionReadingBuilder(private val vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT) {

    fun build(answers: QaAnswers, letter: GemmaLetter, offered: OfferedCandidates): VerifiedReading {
        val typer = QaValueTyper(offered)
        val notes = mutableListOf<String>()

        val parties = listOfNotNull(
            party(PartyRole.SENDER, answers[QaLabel.SENDER], letter, notes),
            party(PartyRole.ADDRESSEE, answers[QaLabel.RECIPIENT], letter, notes),
            party(PartyRole.CONTACT, answers[QaLabel.CONTACT], letter, notes),
        )
        // The letter's own date has a line of its own and comes first, so that it keeps its meaning if the dates list repeats it.
        val letterDate = answers[QaLabel.LETTERDATE]?.let { typer.date(it) }?.let { VerifiedValue(it, GemmaVocabulary.LETTER_DATE_MEANING) }
        val dates = (
            listOfNotNull(letterDate) +
                values(QaLabel.DATES, answers, notes) { vocab.dateMeanings.map { it.id } }
                    .mapNotNull { (text, meaning) -> typer.date(text)?.let { VerifiedValue(it, meaning) } ?: null.also { notes += "date not typed" } }
            ).distinctBy { it.candidate.id }
        // The amount to pay has a line of its own (TOPAY) and is the only amount with that meaning, so that the invoice total never claims it.
        val (toPay, toPayDate) = toPay(answers, typer)
        val amounts = (
            listOfNotNull(toPay?.let { VerifiedValue(it, GemmaVocabulary.TO_PAY_MEANING) }) +
                values(QaLabel.AMOUNTS, answers, notes) { vocab.amountMeanings.map { it.id } }
                    .mapNotNull { (text, meaning) ->
                        typer.amount(text)?.let { VerifiedValue(it, meaning?.takeUnless { m -> m == GemmaVocabulary.TO_PAY_MEANING }) } ?: null.also { notes += "amount not typed" }
                    }
            ).distinctBy { it.candidate.id }
        val references = references(answers, typer, notes)
        val (asks, actions, extraDates, paidInAsks) = askedActions(answers, typer, dates, toPay, toPayDate, notes)

        val category = QaText.key(answers[QaLabel.TYPE]).let { key -> vocab.categoryIds.firstOrNull { QaText.key(it) == key } }
            ?: GemmaVocabulary.DOCUMENT_CATEGORY.also { if (answers[QaLabel.TYPE] != null) notes += "category is not in the registry" }
        val language = QaText.word(answers[QaLabel.LANGUAGE]).takeIf { GemmaSchema.LANGUAGE_CODE.matches(it) }
        val name = answers[QaLabel.TITLE]?.let(DocumentNameFormat::clean)?.takeIf { it.isNotEmpty() && it.length <= DocumentNameFormat.MAX_CHARS && '\n' !in it }
        val paid = paidInAsks ?: PaidState.of(QaText.word(answers[QaLabel.PAID]))
        val eventKind = vocab.eventKind(QaText.word(answers[QaLabel.EVENT]))

        return VerifiedReading(
            parties = parties, dates = dates + extraDates, amounts = amounts, references = references, actions = actions,
            category = category, language = language, name = name, drops = emptyList(), eventKind = eventKind, asksReader = asks, paid = paid,
            synthesized = typer.synthesized, notes = notes,
        )
    }

    /** `TOPAY: amount — by when`: the typed amount and the typed date (either may be null). */
    private fun toPay(answers: QaAnswers, typer: QaValueTyper): Pair<Candidate?, Candidate?> {
        val parts = QaText.items(answers[QaLabel.TOPAY]).firstOrNull()?.let { QaText.parts(it) }.orEmpty()
        val amount = parts.firstOrNull()?.takeIf { it.isNotEmpty() }?.let(typer::amount)
        val date = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(typer::date)
        return amount to date
    }

    // ── parties ──

    /** [text] is "name | kind"; [kindText] overrides where the kind comes from (the contact has no kind: a person). */
    private fun party(role: PartyRole, text: String?, letter: GemmaLetter, notes: MutableList<String>, kindText: String? = text?.let { QaText.parts(it).getOrNull(1) }): VerifiedParty? {
        val name = text?.let { QaText.parts(it).firstOrNull() }?.trim('*', '"', ' ')?.takeIf { it.isNotEmpty() } ?: return null
        val kind = when {
            role == PartyRole.CONTACT -> PartyKind.PERSON
            else -> PartyKind.entries.firstOrNull { it.name.lowercase() == QaText.word(kindText) } ?: PartyKind.OTHER
        }
        val linked = link(name, letter)
        notes += "${role.name.lowercase()}: ${if (linked != null) "linked to a name of the letter" else "stored as written"}"
        return if (linked != null) VerifiedParty(role, kind, linked.id, null) else VerifiedParty(role, kind, null, name, stated = true)
    }

    /** The name candidate of the letter that holds [name] (the best match: the whole words, then roughly the same words), or null. */
    private fun link(name: String, letter: GemmaLetter): GemmaCandidate? {
        val ranked = letter.candidatesOf(CandidateKind.NAME).mapNotNull { c ->
            val match = QuoteVerifier.verify(name, c.raw)?.match ?: return@mapNotNull null
            Triple(c, match.ordinal, kotlin.math.abs(c.raw.length - name.length))
        }
        return ranked.minWithOrNull(compareBy({ it.second }, { it.third }))?.first
    }

    // ── values ──

    /** The items of a list answer as (value text, meaning id or null for "other"): `date — MEANING`, the meaning one of [ids]. */
    private fun values(label: QaLabel, answers: QaAnswers, notes: MutableList<String>, ids: () -> List<String>): List<Pair<String, String?>> {
        val items = QaText.items(answers[label])
        if (items.isNotEmpty()) notes += "${label.name.lowercase()}: ${items.size} item(s)"
        val known = ids()
        return items.mapNotNull { item ->
            val parts = QaText.parts(item)
            val value = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            value to choose(parts.drop(1).firstOrNull { it.isNotEmpty() }, known)
        }
    }

    private fun references(answers: QaAnswers, typer: QaValueTyper, notes: MutableList<String>): List<VerifiedReference> {
        val out = LinkedHashMap<String, VerifiedReference>()
        val kinds = vocab.referenceKinds
        for ((text, kindWord) in values(QaLabel.REFERENCES, answers, notes) { kinds }) {
            val kind = kindWord ?: GemmaVocabulary.OTHER
            val candidate = typer.reference(text, if (kind == GemmaVocabulary.IBAN_KIND) CandidateKind.IBAN else CandidateKind.REFERENCE)
            out.putIfAbsent(candidate.id, VerifiedReference(candidate, kind))
        }
        // The contact's phone number and e-mail address are values of the letter like any other: stored as references of no kind.
        val contact = answers[QaLabel.CONTACT]?.let { QaText.parts(it) }.orEmpty()
        contact.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { typer.reference(it, CandidateKind.PHONE) }
            ?.let { out.putIfAbsent(it.id, VerifiedReference(it, GemmaVocabulary.OTHER)) }
        contact.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { typer.reference(it, CandidateKind.EMAIL) }
            ?.let { out.putIfAbsent(it.id, VerifiedReference(it, GemmaVocabulary.OTHER)) }
        return out.values.toList()
    }

    private class Asked(val asks: Boolean?, val actions: List<VerifiedAction>, val extraDates: List<VerifiedValue>, val paid: PaidState?) {
        operator fun component1() = asks
        operator fun component2() = actions
        operator fun component3() = extraDates
        operator fun component4() = paid
    }

    /**
     * `ASKS: yes | paid state; kind — by when; kind — by when` (the paid state is the second part of the first item; a model that put the
     * first action in that item, `yes — kind — by when`, or left the paid state out is understood as well). A date the action names that
     * the dates list lacks is added to it (with no meaning).
     */
    private fun askedActions(
        answers: QaAnswers, typer: QaValueTyper, dates: List<VerifiedValue>, toPay: Candidate?, toPayDate: Candidate?, notes: MutableList<String>,
    ): Asked {
        val text = answers[QaLabel.ASKS] ?: return Asked(null, emptyList(), emptyList(), null)
        val items = QaText.items(text)
        val asks = when (QaText.word(items.firstOrNull() ?: text)) {
            GemmaVocabulary.YES -> true
            GemmaVocabulary.NO -> false
            else -> null
        }
        var paid: PaidState? = null
        // The paid state is the part that follows yes / no: taken out of the item whichever item it is in.
        val itemParts = items.map { item ->
            var parts = QaText.parts(item)
            if (parts.firstOrNull()?.let { QaText.word(it) } in setOf(GemmaVocabulary.YES, GemmaVocabulary.NO)) parts = parts.drop(1)
            PaidState.of(QaText.word(parts.firstOrNull()))?.let { state ->
                paid = paid ?: state
                parts = parts.drop(1)
            }
            parts
        }
        if (asks == false) return Asked(false, emptyList(), emptyList(), paid)
        val kindIds = vocab.actionKinds.map { it.id }
        val extra = mutableListOf<VerifiedValue>()
        val actions = itemParts.mapNotNull { parts ->
            val kind = choose(parts.firstOrNull(), kindIds)?.let(vocab::actionKind) ?: return@mapNotNull null
            val amount = if (kind.amountMeaning != null) toPay else null
            // The action's own date, else the date of the amount to pay (a payment action is due when the payment is).
            val date = (parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(typer::date) ?: toPayDate.takeIf { amount != null })?.also { d ->
                if (dates.none { it.candidate.id == d.id } && extra.none { it.candidate.id == d.id }) extra += VerifiedValue(d, null)
            }
            VerifiedAction(kind.id, date?.id, amount?.id)
        }.distinctBy { it.kind }.take(QuestionPrompt.MAX_ASKS)
        notes += "asks=${asks} actions=${actions.size}"
        return Asked(asks ?: actions.isNotEmpty().takeIf { it }, actions, extra, paid)
    }

    /** The id of [ids] that [text] names (its leading word, or all of it, with case and separators ignored); null when it names none. */
    private fun choose(text: String?, ids: List<String>): String? {
        if (text.isNullOrBlank()) return null
        val word = QaText.key(text)
        val whole = text.filter { it.isLetterOrDigit() }.lowercase()
        return ids.firstOrNull { QaText.key(it) == word } ?: ids.firstOrNull { QaText.key(it) == whole }
            ?: ids.filter { QaText.key(it).let { k -> k.isNotEmpty() && whole.startsWith(k) } }.maxByOrNull { it.length }
    }
}
