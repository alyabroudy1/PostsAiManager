package com.postsaimanager.core.domain.extraction.candidates

import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import java.time.LocalDate

/**
 * Builds OCR pages from plain strings, one block per row.
 *
 * - `"a||b"` makes two blocks on the same row (a table cell pair: label left, value right).
 * - a leading `@ADDR `, `@HEAD ` or `@RET ` tags the block as address field / letterhead /
 *   return address.
 */
internal class TestPage(val blocks: List<OcrBlock>, val zones: Map<Int, BlockZone>)

internal fun page(vararg rows: String): TestPage {
    val blocks = ArrayList<OcrBlock>()
    val zones = HashMap<Int, BlockZone>()
    rows.forEachIndexed { r, raw ->
        val y = 0.05f + 0.03f * r
        var row = raw
        var zone: BlockZone? = null
        when {
            row.startsWith("@ADDR ") -> { zone = BlockZone.ADDRESS_FIELD; row = row.removePrefix("@ADDR ") }
            row.startsWith("@HEAD ") -> { zone = BlockZone.LETTERHEAD; row = row.removePrefix("@HEAD ") }
            row.startsWith("@RET ") -> { zone = BlockZone.RETURN_ADDRESS; row = row.removePrefix("@RET ") }
        }
        val cells = row.split("||")
        cells.forEachIndexed { c, cell ->
            val (l, rr) = if (cells.size == 1) 0.1f to 0.9f else if (c == 0) 0.1f to 0.45f else 0.5f + 0.2f * (c - 1) to 0.68f + 0.2f * (c - 1)
            if (zone != null) zones[blocks.size] = zone
            blocks.add(OcrBlock(cell, TextBounds(l, y, rr, y + 0.02f), 0.9f))
        }
    }
    return TestPage(blocks, zones)
}

/** junit-jupiter-params is not on the test classpath; table-driven tests use dynamic tests. */
internal fun <T> table(rows: List<T>, name: (T) -> String = { it.toString() }, body: (T) -> Unit): List<org.junit.jupiter.api.DynamicTest> =
    rows.map { row -> org.junit.jupiter.api.DynamicTest.dynamicTest(name(row)) { body(row) } }

internal fun run(vararg pages: TestPage, letterDate: LocalDate? = null): CandidateSet {
    val zones = HashMap<BlockKey, BlockZone>()
    pages.forEachIndexed { p, tp -> tp.zones.forEach { (i, z) -> zones[BlockKey(p + 1, i)] = z } }
    return CandidateExtractor.extract(pages.map { it.blocks }, zones, letterDate)
}
