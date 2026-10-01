package com.postsaimanager.core.domain.form

import com.postsaimanager.core.domain.benchmark.BenchmarkFixtures
import com.postsaimanager.core.model.OcrBlock

/** The invented forms of `src/test/resources/forms/` as stored OCR (one block list per page). */
object FormFixtures {
    const val GERMAN = "F1-de-swim-course-2p"
    const val ENGLISH = "F2-en-school-trip-1p"
    const val ARABIC = "F3-ar-club-membership-1p"

    fun pages(key: String): List<List<OcrBlock>> {
        val text = FormFixtures::class.java.getResourceAsStream("/forms/$key.json")!!.bufferedReader().use { it.readText() }
        return BenchmarkFixtures.parseFixture(text).pages.map { it.blocks }
    }
}
