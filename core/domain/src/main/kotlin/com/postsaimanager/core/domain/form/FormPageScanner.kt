package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.NormBox
import kotlin.math.max
import kotlin.math.min

/** A blank found on one page, before sections and orders are known. */
internal class RawField(
    val rowIndex: Int,
    val uLeft: Float,
    val label: String,
    val labelBox: NormBox?,
    val fillBox: NormBox?,
    val kind: FormFieldKind,
    val evidence: FieldEvidence,
    val options: List<String> = emptyList(),
    val alreadyFilled: String? = null,
)

/**
 * Finds the blanks of one [FormPage] by geometry and shape only (no word is read): box glyphs and their option groups, fill
 * runs, empty table cells, a label followed by empty space or by an empty line. What each blank asks for is not decided here.
 * The passes run from the most structural to the weakest; a token a pass used is not used again by a later one.
 */
internal class FormPageScanner(private val page: FormPage) {

    private val used = HashSet<Tok>()
    private val out = ArrayList<RawField>()
    private val headingRows: Set<Int> = page.rows.filter(page::isHeading).map { it.index }.toSet()

    private val rows get() = page.rows.filter { it.index !in headingRows }

    /** The heading-like rows as (row index, cleaned text). */
    fun headings(): List<Pair<Int, String>> =
        page.rows.filter { it.index in headingRows }.mapNotNull { r -> FormShapes.cleanLabel(r.toks.first().text)?.let { r.index to it } }

    /**
     * The row index of the form's title: the page's first heading-like line when it stands above every blank and is set larger than
     * every heading after it (a title is not the section of the fields below a real heading). A lone heading is a section: with
     * nothing after it there is nothing to tell a title from a section by.
     */
    fun titleRow(firstBlankRow: Int?): Int? {
        val first = page.rows.firstOrNull { it.index in headingRows } ?: return null
        if (firstBlankRow != null && first.index > firstBlankRow) return null
        val later = page.rows.filter { it.index in headingRows && it.index > first.index }.maxOfOrNull { it.toks.first().height } ?: return null
        return first.index.takeIf { first.toks.first().height > TITLE_OVER_LATER * later }
    }

    fun scan(): List<RawField> {
        boxes()
        runs()
        optionRows()
        tables()
        labelSpace()
        return out.filterNot { !it.evidence.strong && inEdgeBand(it) }
    }

    /** A weak blank in the top or bottom margin is a letterhead, a page number or a footer, never a field. */
    private fun inEdgeBand(f: RawField): Boolean {
        val box = f.labelBox ?: return false
        return box.bottom <= EDGE_BAND || box.top >= 1f - EDGE_BAND
    }

    // ── Options whose boxes were drawn ──

    /**
     * A row of short evenly spaced items after a label (on the row before it, or at its start), with no fill or box glyph, is one
     * choice: the items are its options. The label may be a heading-like line: a section that is only a choice.
     */
    private fun optionRows() {
        for (row in rows) {
            val toks = row.toks
            if (toks.any { it in used || it.kind != TokKind.TEXT }) continue
            val lead = toks.first()
            val ledByLabel = toks.size > 2 && (FormShapes.endsWithColon(lead.text) || lead.text.trimEnd().endsWith('?') || lead.text.length > MAX_OPTION_CHARS) &&
                page.isOptionItems(toks.drop(1))
            val label: Tok
            val items: List<Tok>
            if (ledByLabel) {
                label = lead
                items = toks.drop(1)
            } else if (page.isOptionItems(toks)) {
                label = optionLabelAbove(row) ?: continue
                items = toks
            } else {
                continue
            }
            val name = FormShapes.cleanLabel(label.text) ?: continue
            used += label
            used += items
            out += RawField(
                row.index, label.uLeft, name, page.box(label), union(items), FormFieldKind.CHOICE, FieldEvidence.OPTION_ROW,
                options = items.mapNotNull { FormShapes.cleanLabel(it.text) },
            )
        }
    }

    /** The label of an option row: the lone line right above it (a heading-like line included), when it is unused and has no fill. */
    private fun optionLabelAbove(row: FormRow): Tok? {
        val prev = page.rows.getOrNull(row.index - 1) ?: return null
        if (prev.has(TokKind.RUN) || prev.has(TokKind.BOX) || row.top - prev.bottom > MAX_LABEL_GAP * row.height) return null
        return prev.texts.singleOrNull()?.takeIf { it !in used && it.hasLetter && prev.toks.size == 1 }
    }

    // ── Box glyphs ──

    private class Single(val row: FormRow, val box: Tok, val text: Tok)

    private fun boxes() {
        val column = ArrayList<Single>()
        fun flush() {
            if (column.isEmpty()) return
            emitColumn(column.toList())
            column.clear()
        }
        for (row in rows) {
            val boxes = row.toks.filter { it.kind == TokKind.BOX }
            if (boxes.isEmpty()) {
                flush()
                continue
            }
            boxes.forEach { used += it }
            val pairs = boxes.map { b -> b to row.toks.getOrNull(row.toks.indexOf(b) + 1)?.takeIf { it.kind == TokKind.TEXT } }
            pairs.forEach { (_, t) -> t?.let { used += it } }
            val lead = row.toks.takeWhile { it !== boxes.first() }.lastOrNull { it.kind == TokKind.TEXT }
            when {
                pairs.size >= 2 -> {
                    flush()
                    val label = lead ?: labelAbove(row)
                    label?.let { used += it }
                    val withText = pairs.filter { it.second != null }
                    emitChoice(row.index, label, withText.map { it.first to it.second!! })
                }
                lead != null -> {
                    flush()
                    used += lead
                    emitCheckbox(row.index, lead.text, boxes.first(), pairs.first().second, lead)
                }
                pairs.first().second == null -> {
                    flush()
                    val label = labelAbove(row)
                    label?.let { used += it }
                    if (label != null) emitCheckbox(row.index, label.text, boxes.first(), null, label)
                }
                else -> {
                    val (b, t) = pairs.first()
                    val last = column.lastOrNull()
                    val sameColumn = last != null && kotlin.math.abs(last.box.uLeft - b.uLeft) <= COLUMN_TOLERANCE &&
                        row.top - last.row.bottom <= PITCH_FACTOR * row.height
                    if (!sameColumn) flush()
                    column += Single(row, b, t!!)
                }
            }
        }
        flush()
    }

    private fun emitColumn(group: List<Single>) {
        val first = group.first()
        val label = labelAbove(first.row)
        val options = group.map { it.text.text }
        if (group.size >= 2 && label != null && options.sumOf { it.length } / options.size <= MAX_OPTION_CHARS) {
            used += label
            emitChoice(first.row.index, label, group.map { it.box to it.text })
        } else {
            group.forEach { emitCheckbox(it.row.index, it.text.text, it.box, it.text, null) }
        }
    }

    private fun emitChoice(rowIndex: Int, label: Tok?, options: List<Pair<Tok, Tok>>) {
        if (options.isEmpty()) return
        val toks = options.flatMap { listOf(it.first, it.second) }
        val checked = options.filter { it.first.checked }.joinToString(", ") { it.second.text }.ifEmpty { null }
        val name = label?.let { FormShapes.cleanLabel(it.text) } ?: return
        out += RawField(
            rowIndex, label.uLeft, name, page.box(label), union(toks), FormFieldKind.CHOICE, FieldEvidence.BOX_GLYPH,
            options = options.mapNotNull { FormShapes.cleanLabel(it.second.text) }, alreadyFilled = checked,
        )
    }

    private fun emitCheckbox(rowIndex: Int, labelText: String, box: Tok, option: Tok?, label: Tok?) {
        val name = FormShapes.cleanLabel(labelText) ?: return
        val fill = union(listOfNotNull(box, option))
        out += RawField(
            rowIndex, (label ?: option ?: box).uLeft, name, label?.let(page::box) ?: option?.let(page::box), fill,
            FormFieldKind.CHECKBOX, FieldEvidence.BOX_GLYPH, alreadyFilled = if (box.checked) name else null,
        )
    }

    // ── Fill runs ──

    private fun runs() {
        for (row in rows) {
            var pending: Tok? = null
            for (t in row.toks) {
                when (t.kind) {
                    TokKind.TEXT -> if (t !in used) pending = t
                    TokKind.BOX -> pending = null
                    TokKind.RUN -> {
                        val before = pending?.takeIf { it !in used }
                        val label = before ?: nearbyLabel(row, t)
                        pending = null
                        label?.let { used += it }
                        val name = label?.let { FormShapes.cleanLabel(it.text) } ?: continue
                        val filled = textInside(t)
                        filled.forEach { used += it }
                        out += RawField(
                            row.index, label.uLeft, name, page.box(label), page.box(t), FormFieldKind.TEXT, FieldEvidence.FILL_RUN,
                            alreadyFilled = filled.joinToString(" ") { it.text }.ifEmpty { null },
                        )
                    }
                }
            }
        }
    }

    /** The label of a run that has none on its row: the line above it, or a caption under it, whichever is nearer and over the run. */
    private fun nearbyLabel(row: FormRow, run: Tok): Tok? {
        val above = labelAbove(row)?.takeIf { overlaps(it, run) }
        val belowRow = page.rows.getOrNull(row.index + 1)?.takeIf {
            it.index !in headingRows && !it.has(TokKind.RUN) && !it.has(TokKind.BOX) && it.top - row.bottom <= MAX_LABEL_GAP * row.height
        }
        val below = belowRow?.texts?.firstOrNull { it !in used && it.hasLetter && overlaps(it, run) }
        return when {
            above != null && below != null && belowRow != null ->
                if (row.top - above.bottom <= belowRow.top - row.bottom) above else below
            else -> above ?: below
        }
    }

    /** Text of other lines that sits inside the run's region: the blank was filled by hand. */
    private fun textInside(run: Tok): List<Tok> {
        val slack = 0.5f * run.height
        return page.rows.flatMap { it.toks }.filter {
            it.kind == TokKind.TEXT && it !in used && it.lineId != run.lineId &&
                it.centerY in (run.top - slack)..(run.bottom + slack) && it.centerU in run.uLeft..run.uRight
        }.sortedBy { it.uLeft }
    }

    // ── Tables ──

    private fun tables() {
        for (row in rows) {
            if (row.toks.any { it.kind != TokKind.TEXT || it in used }) continue
            val cells = row.toks
            if (cells.size < 2 || cells.any { FormShapes.endsWithColon(it.text) || it.width > MAX_CELL_WIDTH || !it.hasLetter }) continue
            val limit = row.bottom + TABLE_WINDOW * row.height
            val below = page.rows.drop(row.index + 1).takeWhile { it.top <= limit }.flatMap { it.toks }.filter { it.centerY > row.bottom && it.centerY <= limit }
            val under = cells.map { c -> c to below.filter { overlapsRange(it, c.uLeft - CELL_SLACK, c.uRight + CELL_SLACK) } }
            if (under.none { it.second.isEmpty() }) continue
            for ((c, filled) in under) {
                val name = FormShapes.cleanLabel(c.text) ?: continue
                used += c
                filled.forEach { used += it }
                out += RawField(
                    row.index, c.uLeft, name, page.box(c),
                    page.box(c.uLeft, c.uRight, row.bottom + 0.1f * row.height, row.bottom + 1.7f * row.height),
                    FormFieldKind.TABLE_CELL, FieldEvidence.TABLE_CELL,
                    alreadyFilled = filled.filter { it.kind == TokKind.TEXT }.joinToString(" ") { it.text }.ifEmpty { null },
                )
            }
        }
    }

    // ── A label followed by empty space or an empty line ──

    private fun labelSpace() {
        for (row in rows) {
            val toks = row.toks
            for ((i, t) in toks.withIndex()) {
                if (t.kind != TokKind.TEXT || t in used || !t.hasLetter) continue
                if (t.width > MAX_LABEL_WIDTH || t.text.length > MAX_LABEL_CHARS) continue
                val colon = FormShapes.endsWithColon(t.text)
                if (!colon && isParagraphLine(row, t)) continue
                val name = FormShapes.cleanLabel(t.text) ?: continue
                val next = toks.drop(i + 1).firstOrNull { it !in used }
                if (colon && next?.kind == TokKind.TEXT && next.uLeft - t.uRight <= VALUE_GAP && !FormShapes.endsWithColon(next.text)) {
                    used += next
                    out += RawField(row.index, t.uLeft, name, page.box(t), page.box(next), FormFieldKind.TEXT, FieldEvidence.LABEL_SPACE, alreadyFilled = next.text)
                    continue
                }
                val end = next?.uLeft ?: RIGHT_MARGIN
                if (end - t.uRight >= MIN_FILL_GAP) {
                    out += RawField(
                        row.index, t.uLeft, name, page.box(t), page.box(t.uRight + EDGE, end - EDGE, t.top, t.bottom),
                        FormFieldKind.TEXT, FieldEvidence.LABEL_SPACE,
                    )
                } else if (colon && blankBelow(row)) {
                    out += RawField(
                        row.index, t.uLeft, name, page.box(t),
                        page.box(t.uLeft, RIGHT_MARGIN, row.bottom, row.bottom + row.height),
                        FormFieldKind.TEXT, FieldEvidence.LINE_UNDER_LABEL,
                    )
                }
            }
        }
    }

    /** A last line of a paragraph also has space to its right: it stands next to a long line directly above or below. */
    private fun isParagraphLine(row: FormRow, t: Tok): Boolean {
        fun wide(r: FormRow?, gap: Float) = r != null && gap <= PITCH_FACTOR * row.height && r.texts.any { it.width >= PARAGRAPH_WIDTH }
        val prev = page.rows.getOrNull(row.index - 1)
        val next = page.rows.getOrNull(row.index + 1)
        return wide(prev, row.top - (prev?.bottom ?: 0f)) || wide(next, (next?.top ?: 0f) - row.bottom)
    }

    private fun blankBelow(row: FormRow): Boolean {
        val next = page.rows.getOrNull(row.index + 1) ?: return true
        return next.top - row.bottom >= BLANK_LINES * row.height
    }

    // ── Helpers ──

    private fun labelAbove(row: FormRow): Tok? {
        val prev = page.rows.getOrNull(row.index - 1) ?: return null
        if (prev.index in headingRows || prev.has(TokKind.RUN) || prev.has(TokKind.BOX)) return null
        if (row.top - prev.bottom > MAX_LABEL_GAP * row.height) return null
        return prev.texts.firstOrNull { it !in used && it.hasLetter }
    }

    private fun overlaps(a: Tok, b: Tok) = min(a.uRight, b.uRight) - max(a.uLeft, b.uLeft) > 0f

    private fun overlapsRange(t: Tok, left: Float, right: Float) = min(t.uRight, right) - max(t.uLeft, left) > 0f

    private fun union(toks: List<Tok>): NormBox =
        page.box(toks.minOf { it.uLeft }, toks.maxOf { it.uRight }, toks.minOf { it.top }, toks.maxOf { it.bottom })

    private companion object {
        const val COLUMN_TOLERANCE = 0.02f
        const val PITCH_FACTOR = 2.2f
        const val MAX_OPTION_CHARS = 28
        const val MAX_LABEL_GAP = 1.5f
        const val MAX_CELL_WIDTH = 0.4f
        const val CELL_SLACK = 0.01f
        const val TABLE_WINDOW = 1.8f
        const val MAX_LABEL_WIDTH = 0.5f
        const val MAX_LABEL_CHARS = 45
        const val VALUE_GAP = 0.08f
        const val RIGHT_MARGIN = 0.97f
        const val MIN_FILL_GAP = 0.12f
        const val EDGE = 0.005f
        const val PARAGRAPH_WIDTH = 0.55f
        const val BLANK_LINES = 1.9f
        const val EDGE_BAND = 0.07f
        const val TITLE_OVER_LATER = 1.1f
    }
}
