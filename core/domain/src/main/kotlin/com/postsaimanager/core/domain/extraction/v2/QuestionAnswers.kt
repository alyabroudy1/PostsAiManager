package com.postsaimanager.core.domain.extraction.v2

/** The document type, language and confidence the type question was answered with. */
class TypeAnswer(val typeId: String, val language: String, val confidence: String)

/** One party as one entry of a party answer, in the answer's own words. */
class PartyAnswer(val id: String, val kind: String, val relation: String?, val name: String, val confidence: String)

/**
 * Reads the questionnaire's answers (the shapes [QuestionGrammars] writes) into the raw types the
 * verifier already takes. Lenient by design: the grammar makes a malformed answer impossible, but an
 * answer can still be cut off at its token limit, so anything that does not parse is simply not an
 * answer (null, or the entries before the broken one) and the verifier never sees it.
 */
internal object AnswerReader {

    private sealed interface Tok {
        class Word(val text: String) : Tok
        class Quote(val text: String) : Tok
        data object Semi : Tok
    }

    private fun tokens(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' || c == '\n' || c == '\r' -> i++
                c == ';' -> {
                    out += Tok.Semi
                    i++
                }
                c == '"' -> {
                    val end = text.indexOf('"', i + 1)
                    if (end < 0) return out // cut off inside a string: the rest is not an answer
                    out += Tok.Quote(text.substring(i + 1, end))
                    i = end + 1
                }
                else -> {
                    var j = i
                    while (j < text.length && text[j] != ' ' && text[j] != ';' && text[j] != '\n' && text[j] != '"') j++
                    out += Tok.Word(text.substring(i, j))
                    i = j
                }
            }
        }
        return out
    }

    private fun List<Tok>.split(): List<List<Tok>> {
        val entries = ArrayList<List<Tok>>()
        var current = ArrayList<Tok>()
        for (t in this) {
            if (t is Tok.Semi) {
                entries += current
                current = ArrayList()
            } else {
                current += t
            }
        }
        entries += current
        return entries.filter { it.isNotEmpty() }
    }

    private fun List<Tok>.word(i: Int): String? = (getOrNull(i) as? Tok.Word)?.text

    private fun List<Tok>.text(i: Int): String? = when (val t = getOrNull(i)) {
        is Tok.Word -> t.text
        is Tok.Quote -> t.text
        else -> null
    }

    private fun isNone(toks: List<Tok>) = toks.size == 1 && toks[0].let { it is Tok.Word && it.text == QuestionGrammars.NONE }

    fun type(answer: String): TypeAnswer? {
        val t = tokens(answer)
        val type = t.word(0) ?: return null
        val lang = t.word(1) ?: return null
        return TypeAnswer(type, lang, t.word(2) ?: "MEDIUM")
    }

    /** Every complete entry of a party answer; an entry cut off at the limit is dropped. */
    fun parties(answer: String, withRelation: Boolean): List<PartyAnswer> {
        val toks = tokens(answer)
        if (isNone(toks)) return emptyList()
        return toks.split().mapNotNull { e ->
            var i = 0
            val id = e.text(i++) ?: return@mapNotNull null
            // A small model sometimes writes NONE as a quoted name: that is still "no such party", not a person called NONE.
            if (id.equals(QuestionGrammars.NONE, ignoreCase = true) && e.first() is Tok.Quote) return@mapNotNull null
            val kind = e.word(i++) ?: return@mapNotNull null
            val relation = if (withRelation) e.word(i++) ?: return@mapNotNull null else null
            val name = (e.getOrNull(i++) as? Tok.Quote)?.text ?: return@mapNotNull null
            val confidence = e.word(i) ?: return@mapNotNull null
            PartyAnswer(id, kind, relation, name, confidence)
        }
    }

    /** A slot's answer, or null for NONE (and for anything that is not a complete answer). */
    fun slot(slot: SlotKey, answer: String): RawSlot? {
        val t = tokens(answer)
        if (t.isEmpty() || isNone(t)) return null
        return when (slot.kind) {
            SlotKind.AMOUNT, SlotKind.DATE -> {
                val id = t.word(0) ?: return null
                RawSlot(id = id, role = t.word(1) ?: return null, confidence = t.word(2) ?: return null)
            }
            SlotKind.DEADLINE -> {
                if (t.word(0) == QuestionGrammars.RULE) {
                    RawSlot(rule = (t.getOrNull(1) as? Tok.Quote)?.text ?: return null, role = t.word(2) ?: return null, confidence = t.word(3) ?: return null)
                } else {
                    RawSlot(id = t.word(0) ?: return null, role = t.word(1) ?: return null, confidence = t.word(2) ?: return null)
                }
            }
            SlotKind.IBAN, SlotKind.REFERENCE, SlotKind.NAME, SlotKind.ACTION ->
                RawSlot(id = t.text(0) ?: return null, confidence = t.word(1) ?: return null)
            SlotKind.REFERENCE_LIST -> {
                val words = t.takeWhile { it is Tok.Word }.map { (it as Tok.Word).text }
                // ids first, the confidence word last
                if (words.size < 2) return null
                RawSlot(ids = words.dropLast(1).take(StructuredGrammar.MAX_REF_IDS), confidence = words.last())
            }
        }
    }

    /** Every complete extra of the extras answer. */
    fun extras(answer: String): List<RawExtra> {
        val toks = tokens(answer)
        if (toks.isEmpty() || isNone(toks)) return emptyList()
        return extraEntries(toks.split())
    }

    /** The language code the first entry holds (null when it is not a bare code) and every complete extra after it. */
    class LanguageAndExtras(val language: String?, val extras: List<RawExtra>)

    /** `de; N4 "label" key "value" MEDIUM; ...` (see [QuestionGrammars.languageAndExtras]). */
    fun languageAndExtras(answer: String): LanguageAndExtras {
        val entries = tokens(answer).split()
        val first = entries.firstOrNull()
        val language = first?.takeIf { it.size == 1 }?.word(0)?.lowercase()?.takeIf { LANGUAGE.matches(it) }
        return LanguageAndExtras(language, extraEntries(entries.drop(1)))
    }

    private val LANGUAGE = Regex("[a-z]{2,3}(-[a-z0-9]+)?")

    private fun extraEntries(entries: List<List<Tok>>): List<RawExtra> {
        return entries.mapNotNull { e ->
            val id = e.word(0) ?: return@mapNotNull null
            val label = (e.getOrNull(1) as? Tok.Quote)?.text ?: return@mapNotNull null
            val key = e.word(2) ?: return@mapNotNull null
            val value = (e.getOrNull(3) as? Tok.Quote)?.text ?: return@mapNotNull null
            val confidence = e.word(4) ?: return@mapNotNull null
            RawExtra(label = label, key = key, id = id, value = value, confidence = confidence)
        }
    }

    /** The one quoted line an answer holds, or null. */
    fun line(answer: String): String? = (tokens(answer).firstOrNull() as? Tok.Quote)?.text

    /** The quoted lines of an answer, in order. */
    fun lines(answer: String): List<String> = tokens(answer).mapNotNull { (it as? Tok.Quote)?.text }
}
