package com.postsaimanager.core.domain.usecase

/**
 * Decides which of a turn's injected passages are worth showing as citation chips (4.3).
 *
 * [SendChatMessageUseCase] labels every passage it injects exactly the way it asks the model
 * to cite it back — `"p.2"`, `"part 3"`, or `"Acme Corp, p.2"` (see its `withPassages`
 * KDoc) — and the instruction it prepends spells that convention out: *"Cite the ones you
 * use like &#91;p.2&#93; or &#91;Document title, p.2&#93;"*. This class is the other half of
 * that contract: once the answer is back, it looks for bracketed citations in the text and
 * matches them, leniently, against each passage's label.
 *
 * ### Why lenient
 *
 * The model is never forced to reproduce a label byte-for-byte, and — this app's whole
 * premise — frequently answers in German: `&#91;S. 2&#93;` and `&#91;Seite 2&#93;` are
 * exactly as valid a citation of `"p.2"` as `&#91;p.2&#93;` or `&#91;page 2&#93;` is. Spacing
 * and case are not meaningful either (`&#91;P.2&#93;`, `&#91;p 2&#93;`). What *is* meaningful
 * is the number: a citation is only ever matched by the page or part number it names, never
 * by exact bracket text.
 *
 * ### Fallback
 *
 * A reader should never see zero grounding just because the model forgot to cite, or cited
 * something [pick] cannot parse — [pick] falls back to showing every injected passage
 * whenever none of them matched a citation in the answer, under the caller's "Based on"
 * label (see `ChatScreen`). Only when at least one passage *is* recognisably cited does the
 * result narrow to just those.
 */
object CitationParser {

    /** One injected passage, labelled exactly as it was shown to the model. */
    data class Labelled<T>(val label: String, val value: T)

    /**
     * Returns the [labelled] passages [answer] cites, or — if [answer] cites none of them —
     * every passage in [labelled] plus [unlabelled], unchanged. Order is preserved ([labelled]
     * then [unlabelled]; already ranked by relevance — citation order in the text is not a
     * stronger signal).
     *
     * @param unlabelled passages that were injected but cannot be matched against a citation
     *   at all — a passage with no page number (a chunk indexed before 4.0's page-aware
     *   chunking) has no label this class can parse a citation out of. They can never be
     *   picked out as specifically cited, but still belong in the "show everything" fallback:
     *   the model was shown them too.
     */
    fun <T> pick(answer: String, labelled: List<Labelled<T>>, unlabelled: List<T> = emptyList()): List<T> {
        if (labelled.isEmpty() && unlabelled.isEmpty()) return emptyList()
        val citations = extractCitations(answer)
        if (citations.isEmpty()) return labelled.map { it.value } + unlabelled

        val cited = labelled.filter { entry -> citations.any { it.matches(entry.label) } }
        return if (cited.isNotEmpty()) cited.map { it.value } else labelled.map { it.value } + unlabelled
    }

    private fun extractCitations(answer: String): List<Citation> =
        BRACKET_REGEX.findAll(answer).mapNotNull { parseCitation(it.groupValues[1]) }.toList()

    private fun parseCitation(bracketText: String): Citation? {
        val part = PART_REGEX.find(bracketText)?.groupValues?.get(1)?.toIntOrNull()
        val page = PAGE_REGEX.find(bracketText)?.groupValues?.get(1)?.toIntOrNull()
        if (part == null && page == null) return null
        return Citation(page = page, part = part, text = bracketText)
    }

    /** A `&#91;p.N&#93;`/`&#91;part K&#93;`/`&#91;title, p.N&#93;`-shaped label, decomposed for matching. */
    private fun parseLabel(label: String): ParsedLabel? {
        PART_REGEX.find(label)?.let { match ->
            return ParsedLabel(title = null, page = null, part = match.groupValues[1].toIntOrNull())
        }
        val pageMatch = PAGE_REGEX.find(label) ?: return null
        val page = pageMatch.groupValues[1].toIntOrNull() ?: return null
        // Everything before the page marker is the title, for the standalone-chat label
        // shape ("Acme Corp, p.2") — blank (no title prefix) for the document-chat shape
        // ("p.2") itself.
        val title = label.substring(0, pageMatch.range.first).trim().trimEnd(',').ifBlank { null }
        return ParsedLabel(title = title, page = page, part = null)
    }

    /** A citation found in the model's answer, parsed out of one bracketed span, e.g. `p.2`. */
    private data class Citation(val page: Int?, val part: Int?, val text: String) {
        fun matches(label: String): Boolean {
            val parsed = parseLabel(label) ?: return false
            val numberMatches = (parsed.page != null && parsed.page == page) ||
                (parsed.part != null && parsed.part == part)
            if (!numberMatches) return false

            val title = parsed.title ?: return true
            // A titled label (standalone chat, several documents in play) additionally
            // needs some sign the citation is actually about that document — otherwise a
            // bare "[p.2]" naming a different document's page would falsely match here too.
            // A loose substring check on the title's first non-trivial word is enough:
            // models paraphrase or truncate titles, not reorder their first word.
            val firstWord = title.trim().split(WHITESPACE_REGEX).firstOrNull { it.length >= MIN_TITLE_WORD_LENGTH }
                ?: return true
            return text.contains(firstWord, ignoreCase = true)
        }
    }

    private data class ParsedLabel(val title: String?, val page: Int?, val part: Int?)

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val BRACKET_REGEX = Regex("\\[([^\\[\\]]{1,120})]")
    private val PART_REGEX = Regex("\\bpart\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)

    /**
     * Page markers in English (`p.`/`p`/`page`) and German (`S.`/`Seite`) — the two languages
     * this app's documents and, per its instruction to the model, its answers are ever in.
     */
    private val PAGE_REGEX = Regex(
        "\\b(?:p\\.?|page|s\\.?|seite)\\s*(\\d+)\\b",
        RegexOption.IGNORE_CASE,
    )

    private const val MIN_TITLE_WORD_LENGTH = 3
}
