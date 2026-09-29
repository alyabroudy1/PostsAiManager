package com.postsaimanager.core.domain.extraction.candidates

/**
 * Names, by shape: a short line of words without digits in an address field, a return address, a
 * letterhead, a footer, or (with no zone) in the top half of page 1. One neutral [CandidateKind.NAME];
 * person or company is the model's call. The zone travels along as a hint only.
 */
internal object NameFinder : CandidateFinder {

    /** Shape-only names are looked for above this height on page 1 (fraction of the page). */
    private const val TOP_HALF = 0.5f

    /**
     * A routing prefix by shape: leading abbreviation tokens such as "z. Hd." or "c/o" (short letters
     * ended by a full stop, or two letters around a slash). Only removed from the name; the words are
     * kept as the candidate's hint. Whether the person is a routing contact is the model's decision.
     */
    private val ROUTING_TOKEN = Regex("^(?:\\p{L}{1,3}\\.|\\p{L}{1,3}/\\p{L}{1,3}\\.?|\\p{L}{1,4}\\.?:)$")
    private val STREET_SHAPE = Regex("^.*\\p{L}\\.?\\s*\\d+\\s?\\p{L}?$")
    private val POSTAL = Regex("^(?:[A-Z]{1,2}[- ])?\\d{4,5}\\s+\\p{L}")
    private val WHITE = Regex("\\s+")
    private val RETURN_SEPARATOR = Regex("\\s*[\u00B7\u2022|]\\s*|\\s+[\u2013\u2014-]\\s+")

    override fun find(ctx: ExtractionContext): List<Draft> {
        val out = ArrayList<Draft>()
        for (line in ctx.activeLines) findIn(ctx, line, out)
        return out
    }

    /** A line of an address block that is not a name: a postcode line, a street with a number, or no letters. */
    private fun looksLikeAddressLine(t: String): Boolean =
        POSTAL.containsMatchIn(t) || STREET_SHAPE.matches(t) || t.any { it.isDigit() } || t.none { it.isLetter() }

    /**
     * Removes a routing prefix by shape ("z. Hd.", "c/o", "Attn:"): two or more short abbreviations
     * ended by a full stop, or a slash abbreviation, or a short word with a colon. Returns the rest
     * and the prefix, or the line and null. The prefix stays as the candidate's hint; whether the
     * person is a routing contact is for the model to say.
     */
    private fun stripRoutingPrefix(s: String): Pair<String, String?> {
        val tokens = s.trim().split(WHITE).filter { it.isNotEmpty() }
        var n = 0
        while (n < tokens.size - 1 && ROUTING_TOKEN.matches(tokens[n])) n++
        if (n == 0) return s to null
        val run = tokens.subList(0, n)
        val isRouting = run.any { it.contains('/') || it.endsWith(':') } ||
            (n >= 2 && run.any { it.trimEnd('.').length >= 2 })
        if (!isRouting) return s to null
        return tokens.drop(n).joinToString(" ") to run.joinToString(" ")
    }

    /** Words only: no digit, no `@`, no web address, 1..6 words, at most 60 characters. Any script, no word list. */
    private fun nameShape(s: String, minWords: Int): Boolean {
        if (s.length !in 2..60 || s.any { it.isDigit() } || s.contains('@') || s.contains("://") || s.contains("www.", true)) return false
        if (s.any { it in ":;!?" }) return false
        val words = s.split(WHITE).filter { it.isNotEmpty() }
        if (words.size !in 1..6 || s.endsWith(',')) return false
        return words.size >= minWords
    }

    /** One neutral name candidate: its kind (person, company, authority) is the model's to decide. */
    private fun name(
        ctx: ExtractionContext,
        line: SourceLine,
        range: IntRange,
        value: String,
        label: String,
        attrs: Map<String, String>,
    ): Draft = ctx.draft(line, range, CandidateKind.NAME, value, value, label = label, attrs = attrs)

    /**
     * A name candidate from an address-field line: shape only. The line stays whole (a form of address
     * such as "Herrn" or "Mrs" is part of it); only a routing prefix is cut off by its shape and kept as
     * the hint. The model returns the party's normalised name and code checks it against this text.
     */
    private fun fromAddressLine(
        ctx: ExtractionContext,
        line: SourceLine,
        out: MutableList<Draft>,
        candidate: String,
        extra: Map<String, String>,
        minWords: Int = 2,
    ): Boolean {
        val trimmed = candidate.trim().trimEnd(',', ';')
        val (text, prefix) = stripRoutingPrefix(trimmed)
        if (text.isBlank() || !nameShape(text, minWords)) return false
        val start = line.text.indexOf(text).coerceAtLeast(0)
        val range = start until (start + text.length)
        val attrs = extra + listOfNotNull(if (prefix != null) "prefix" to prefix else null)
        out += name(ctx, line, range, text, prefix.orEmpty(), attrs)
        return true
    }

    /** "Familie Beispiel": [combined] is the two one-word lines joined, kept whole. */
    private fun fromJoinedLines(
        ctx: ExtractionContext,
        line: SourceLine,
        out: MutableList<Draft>,
        combined: String,
        extra: Map<String, String>,
    ): Boolean {
        if (combined.isBlank() || !nameShape(combined, 1)) return false
        out += name(ctx, line, 0 until line.text.length, combined, "", extra)
        return true
    }

    private fun findIn(ctx: ExtractionContext, line: SourceLine, out: MutableList<Draft>) {
        val t = line.text
        val zoneAttr = line.zone?.let { mapOf("zone" to it.name) } ?: emptyMap()
        when (line.zone) {
            BlockZone.ADDRESS_FIELD -> {
                if (looksLikeAddressLine(t)) return
                // A one-word line under a one-word line ("Familie" / "Beispiel", "Herrn" / "Mustermann")
                // is one name; by shape, whatever the first word says.
                val prev = ctx.lines.getOrNull(line.order - 1)
                    ?.takeIf { it.page == line.page && it.zone == line.zone }
                    ?.text?.trim()
                if (t.split(WHITE).size == 1 && prev != null && prev.split(WHITE).size == 1 && !looksLikeAddressLine(prev)) {
                    if (fromJoinedLines(ctx, line, out, "$prev $t", zoneAttr)) return
                }
                fromAddressLine(ctx, line, out, t, zoneAttr)
            }
            BlockZone.RETURN_ADDRESS -> {
                val first = t.split(RETURN_SEPARATOR).firstOrNull { it.isNotBlank() }?.trim() ?: return
                if (first.any { it.isDigit() }) return
                fromAddressLine(ctx, line, out, first, zoneAttr, minWords = 1)
            }
            BlockZone.LETTERHEAD -> {
                // Contact and legal lines are recognised by shape (digits, colon, @, web address), not by words.
                if (looksLikeAddressLine(t) || t.count { it.isDigit() } >= 3 || t.contains(':') || t.contains('@') ||
                    t.contains("www.", true)
                ) return
                if (t.length in 3..60) {
                    val first = out.none { it.line.page == line.page && it.line.blockIndex == line.blockIndex && it.c.attrs["zone"] == "LETTERHEAD" }
                    val single = t.split(WHITE).size == 1
                    out += name(ctx, line, 0 until t.length, t, "", if (first && single) zoneAttr + ("guess" to "true") else zoneAttr)
                }
            }
            BlockZone.FOOTER -> shapeName(ctx, line, out, footer = true)
            null -> shapeName(ctx, line, out, footer = false)
        }
    }

    /**
     * A name found from its shape alone: a short line of words with no digit, no `@` and no web
     * address, that is not a sentence or a label (it does not end in `, : ; ! ?`, nor in a full stop
     * unless the last word is an abbreviation such as "Ltd."). On page 1 that means the top half of
     * the page; in a footer any line. Capitalisation is not required, so scripts without case work.
     *
     * The zone, when there is one, only travels along as a hint: which of these is the sender and
     * which the addressee is for the model to say.
     */
    private fun shapeName(ctx: ExtractionContext, line: SourceLine, out: MutableList<Draft>, footer: Boolean) {
        val b = line.block?.bounds ?: return
        if (!footer && !(line.page == 1 && b.top < TOP_HALF)) return
        val t = line.text.trim()
        if (t.length !in 3..60 || t.any { it.isDigit() } || t.contains('@') || t.contains("://") || t.contains("www.", true)) return
        if (t.contains(':') || t.last() in ",;!?") return
        val words = t.split(WHITE).filter { it.isNotEmpty() }
        if (words.size !in 1..6) return
        if (t.endsWith('.') && words.last().length > 4) return
        val (text, prefix) = stripRoutingPrefix(t)
        if (text.isBlank() || text.length < 3) return
        val zoneAttr = line.zone?.let { mapOf("zone" to it.name) } ?: emptyMap()
        val attrs = zoneAttr + ("shape" to "true")
        val range = 0 until t.length
        // One neutral candidate, the whole line; a single word is a weaker guess. Person, company or
        // authority, and the name without any form of address, are the model's call.
        val single = text.split(WHITE).size < 2
        val flags = listOfNotNull(
            if (single) "guess" to "true" else null,
            if (prefix != null) "prefix" to prefix else null,
        )
        out += name(ctx, line, range, text, prefix.orEmpty(), attrs + flags)
    }
}
