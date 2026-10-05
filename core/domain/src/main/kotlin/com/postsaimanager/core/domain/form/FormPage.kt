package com.postsaimanager.core.domain.form

import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.model.NormBox
import kotlin.math.max
import kotlin.math.min

/** Tokens that share a visual row, in reading order. */
internal class FormRow(val index: Int, val toks: List<Tok>) {
    val top: Float = toks.minOf { it.top }
    val bottom: Float = toks.maxOf { it.bottom }
    val height: Float = median(toks.map { it.height })
    val texts: List<Tok> get() = toks.filter { it.kind == TokKind.TEXT }
    fun has(kind: TokKind) = toks.any { it.kind == kind }
}

/** One page as rows of tokens, with what the geometric steps need to know about it. */
internal class FormPage(val number: Int, val rtl: Boolean, val rows: List<FormRow>, val medianHeight: Float) {

    /**
     * A row that is one short line with no fill of its own, by shape: set noticeably larger than the page's text, or (when it is
     * set only slightly larger, as a bold line of the same size often is) one that leads a block: a colon-less line with more
     * space above than the lines have between them, followed by a line of labels or fields rather than by options.
     */
    fun isHeading(row: FormRow): Boolean {
        val only = row.toks.singleOrNull() ?: return false
        if (only.kind != TokKind.TEXT || !only.hasLetter || only.width > HEADING_MAX_WIDTH) return false
        if (only.height >= HEADING_FACTOR * medianHeight) return true
        if (FormShapes.endsWithColon(only.text) || only.text.trimEnd().endsWith('?') || only.text.length > HEADING_MAX_CHARS) return false
        val prev = rows.getOrNull(row.index - 1)
        val next = rows.getOrNull(row.index + 1) ?: return false
        val spaced = prev == null || row.top - prev.bottom >= BLOCK_GAP * row.height
        val sameSize = only.height >= SOFT_HEADING_FACTOR * medianHeight
        return spaced && sameSize && next.texts.isNotEmpty() && !next.has(TokKind.BOX) && !isOptionRow(next) &&
            next.top - row.bottom <= BLOCK_GAP * row.height
    }

    /**
     * A row of two to six short items spread evenly along it with no fill characters: the printed options of a choice whose boxes
     * were drawn (invisible to OCR), so nothing marks them but their shape.
     */
    fun isOptionRow(row: FormRow): Boolean = isOptionItems(row.toks)

    /** [items] (tokens of one row, in reading order) are such options. */
    fun isOptionItems(items: List<Tok>): Boolean {
        if (items.size !in 2..MAX_OPTIONS || items.any { it.kind != TokKind.TEXT || !it.hasLetterOrDigit || it.text.length > MAX_OPTION_CHARS }) return false
        if (items.any { FormShapes.endsWithColon(it.text) }) return false
        val gaps = items.zipWithNext { a, b -> b.uLeft - a.uRight }
        if (gaps.any { it < MIN_OPTION_GAP }) return false
        return gaps.max() <= EVEN_FACTOR * gaps.min() + EVEN_SLACK
    }

    /** The box of a region given in reading-direction coordinates, in page coordinates. */
    fun box(uLeft: Float, uRight: Float, top: Float, bottom: Float): NormBox {
        val l = if (rtl) 1f - uRight else uLeft
        val r = if (rtl) 1f - uLeft else uRight
        return NormBox(l.coerceIn(0f, 1f), top.coerceIn(0f, 1f), r.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
    }

    fun box(t: Tok): NormBox = box(t.uLeft, t.uRight, t.top, t.bottom)

    val text: String = rows.joinToString("\n") { r -> r.toks.joinToString(" ") { it.text } }

    companion object {
        private const val HEADING_FACTOR = 1.25f
        private const val SOFT_HEADING_FACTOR = 0.95f
        private const val HEADING_MAX_WIDTH = 0.9f
        private const val HEADING_MAX_CHARS = 40
        private const val BLOCK_GAP = 1.5f
        private const val MAX_OPTIONS = 6
        private const val MAX_OPTION_CHARS = 28
        private const val MIN_OPTION_GAP = 0.015f
        private const val EVEN_FACTOR = 3f
        private const val EVEN_SLACK = 0.04f

        /** Lines closer than this (or half their height) vertically are on one row. */
        private const val ROW_TOLERANCE = 0.006f

        /**
         * The page of [lines]. The analyzer's noise flags are ignored on purpose: a run of underscores reads as "symbol soup" to it,
         * and a form's repeated header is still the form.
         */
        fun build(number: Int, lines: List<LayoutLine>): FormPage {
            val rtl = LetterLayoutAnalyzer.isRightToLeftText(lines.map { it.text })
            val toks = lines.flatMapIndexed { i, l -> FormShapes.tokenize(l, i, rtl) }.sortedBy { it.centerY }
            val grouped = ArrayList<MutableList<Tok>>()
            for (t in toks) {
                val row = grouped.lastOrNull()
                val anchor = row?.first()
                val tol = if (anchor == null) 0f else max(ROW_TOLERANCE, 0.5f * min(anchor.height, t.height))
                if (row != null && kotlin.math.abs(t.centerY - anchor!!.centerY) <= tol) row += t else grouped += mutableListOf(t)
            }
            val rows = grouped.mapIndexed { i, r -> FormRow(i, r.sortedBy { it.uLeft }) }
            val medianHeight = median(toks.filter { it.kind == TokKind.TEXT }.map { it.height })
            return FormPage(number, rtl, rows, medianHeight)
        }
    }
}

internal fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
}
