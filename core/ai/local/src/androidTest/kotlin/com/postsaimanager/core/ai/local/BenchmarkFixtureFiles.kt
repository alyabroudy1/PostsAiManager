package com.postsaimanager.core.ai.local

import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.json.JSONObject
import java.io.File

/** Reads a staged benchmark fixture (`bench/<key>.json`: the recognised pages of an invented letter) for the device recordings. */
internal object BenchmarkFixtureFiles {

    /** The pages' blocks in page order, and the first page's aspect ratio (width over height) when the fixture has it. */
    fun parse(file: File): Pair<List<List<OcrBlock>>, Float?> {
        val pages = JSONObject(file.readText()).getJSONArray("pages")
        val sorted = (0 until pages.length()).map { pages.getJSONObject(it) }.sortedBy { it.getInt("pageNumber") }
        val blocks = sorted.map { p ->
            val bs = p.getJSONArray("blocks")
            (0 until bs.length()).map { i ->
                val b = bs.getJSONObject(i)
                val r = b.getJSONObject("bounds")
                OcrBlock(
                    text = b.getString("text"),
                    bounds = TextBounds(
                        r.getDouble("left").toFloat(), r.getDouble("top").toFloat(),
                        r.getDouble("right").toFloat(), r.getDouble("bottom").toFloat(),
                    ),
                    confidence = b.optDouble("confidence", 1.0).toFloat(),
                    language = b.optString("language").takeIf { it.isNotEmpty() && it != "null" },
                )
            }
        }
        val first = sorted.firstOrNull()
        val aspect = first?.let { p -> p.optDouble("width", 0.0) / p.optDouble("height", 1.0) }?.takeIf { it > 0.0 }?.toFloat()
        return blocks to aspect
    }
}
