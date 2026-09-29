package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds

/**
 * Builds OCR pages with the geometry of a DIN 5008 letter (fractions of the page): letterhead,
 * Rücksendeangabe, the address field under it, a right-hand info block, the subject, body lines and
 * a three-column footer. The same geometry the layout tests are tuned to.
 */
internal object Din {

    fun b(text: String, x: Float, y: Float, conf: Float = 0.9f, w: Float? = null): OcrBlock {
        val width = w ?: (text.lines().maxOf { it.length } * 0.0075f).coerceAtMost(0.95f - x)
        val lines = text.lines().size
        return OcrBlock(text, TextBounds(x, y - 0.007f, x + width, y + 0.007f + 0.016f * (lines - 1)), conf)
    }

    /** One body row; `"a||b||c"` makes table cells side by side. */
    fun row(text: String, y: Float): List<OcrBlock> {
        val cells = text.split("||")
        if (cells.size == 1) return listOf(b(text, 0.117f, y))
        val xs = listOf(0.117f, 0.50f, 0.64f, 0.78f)
        return cells.mapIndexed { i, c -> b(c, xs[minOf(i, xs.size - 1)], y, w = if (i == 0) 0.36f else 0.12f) }
    }

    class Sender(
        val letterhead: List<String>,
        val returnLine: String?,
        val right: Boolean = true,
        val footer: List<String>,
    )

    fun firstPage(
        sender: Sender,
        address: List<String>,
        addressAsOneBlock: Boolean = false,
        info: List<Pair<String, String>> = emptyList(),
        /** A date line at the right, as on letters that have no labelled info block. */
        dateLine: String? = null,
        subject: String,
        body: List<String>,
        totalPages: Int = 1,
    ): List<OcrBlock> {
        val out = mutableListOf<OcrBlock>()
        sender.letterhead.forEachIndexed { i, t ->
            val x = if (sender.right) 0.89f - t.length * 0.0075f else 0.11f
            out += b(t, x, 0.052f + 0.0115f * i)
        }
        sender.returnLine?.let { out += b(it, 0.114f, 0.157f, w = it.length * 0.0045f) }
        if (addressAsOneBlock) {
            out += b(address.joinToString("\n"), 0.115f, 0.178f, w = 0.3f)
        } else {
            address.forEachIndexed { i, t -> out += b(t, 0.115f, 0.178f + 0.016f * i) }
        }
        info.forEachIndexed { i, (label, value) ->
            out += b(label, 0.59f, 0.173f + 0.0145f * i, w = 0.09f)
            out += b(value, 0.70f, 0.173f + 0.0145f * i)
        }
        dateLine?.let { out += b(it, 0.60f, 0.30f) }
        out += b(subject, 0.117f, 0.345f)
        var y = 0.375f
        for (t in body) {
            if (t.isNotEmpty()) out += row(t, y)
            y += 0.0165f
        }
        out += footer(sender, 1, totalPages)
        return out
    }

    fun footer(sender: Sender, page: Int, total: Int): List<OcrBlock> {
        val out = sender.footer.mapIndexed { i, t -> b(t, 0.11f + 0.27f * (i % 3), 0.911f + 0.0105f * (i / 3), w = 0.22f) }
        return out + b("Seite $page von $total", 0.84f, 0.96f)
    }

    fun continuation(sender: Sender, page: Int, total: Int, header: String, body: List<String>): List<OcrBlock> {
        val out = mutableListOf(b(header, 0.11f, 0.05f))
        var y = 0.12f
        for (t in body) {
            if (t.isNotEmpty()) out += row(t, y)
            y += 0.0165f
        }
        return out + footer(sender, page, total)
    }
}
