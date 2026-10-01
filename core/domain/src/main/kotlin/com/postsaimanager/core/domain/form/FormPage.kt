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

    /** A row that is one short line set noticeably larger than the page's text: a heading, by shape. */
    fun isHeading(row: FormRow): Boolean {
        val only = row.toks.singleOrNull() ?: return false
        return only.kind == TokKind.TEXT && only.hasLetter && only.height >= HEADING_FACTOR * medianHeight && only.width <= HEADING_MAX_WIDTH
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
        private const val HEADING_MAX_WIDTH = 0.9f

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
