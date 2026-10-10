package com.postsaimanager.feature.documents

/** How a row of buttons is laid out: [widths] per button, side by side or stacked (each full width). */
internal data class ButtonRowPlan(val sideBySide: Boolean, val widths: List<Int>)

/**
 * Side by side when the natural widths plus the gaps fit in [available]; the spare space is shared so the
 * row is filled: equal widths when each fits in an equal share, otherwise natural width plus an equal share
 * of what is left. Stacked, each full width, only when the sum does not fit.
 */
internal fun planButtonRow(natural: List<Int>, available: Int, gap: Int): ButtonRowPlan {
    val count = natural.size
    val usable = available - gap * (count - 1)
    if (count == 0 || natural.sum() > usable) return ButtonRowPlan(false, natural.map { available })
    val equal = usable / count
    if (natural.all { it <= equal }) {
        val widths = List(count) { equal }.toMutableList()
        widths[count - 1] += usable - equal * count
        return ButtonRowPlan(true, widths)
    }
    val extra = (usable - natural.sum()) / count
    val widths = natural.map { it + extra }.toMutableList()
    widths[count - 1] += usable - widths.sum()
    return ButtonRowPlan(true, widths)
}
