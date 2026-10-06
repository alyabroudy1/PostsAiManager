package com.postsaimanager.core.domain.document

import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.KeySlot

/**
 * Applies what a second stage picked as key information to the stored slot rows: a picked slot's row gets the score as its
 * [ExtractedData.importance], every other slot row loses a pick an earlier reading gave it (the newest reading decides). Extras are not
 * touched (they are key information by being shown). Pure; the caller persists the rows it returns.
 *
 * The model's "does the reader need this" is a lean, not a ranking: a small model leans Yes for most identifier-shaped values, so the
 * picks are capped at [MAX_KEY_SLOTS]. A value the extractor itself is unsure of ([ExtractedData.confidence] under
 * [ConfidenceCombiner.REVIEW_BELOW]) ranks behind every sure one, then by the model's score: what is left stays under "All details".
 */
object KeySlotMarker {

    /** At most this many slot rows are key information, so the essentials stay short whatever the model leaned. */
    const val MAX_KEY_SLOTS = 4

    /**
     * The stored rows whose importance changes, already updated. Empty when [picked] is null (the stage did not score the slots, so what
     * is stored stays).
     */
    fun mark(stored: List<ExtractedData>, picked: List<KeySlot>?): List<ExtractedData> {
        if (picked == null) return emptyList()
        val scores = picked.groupBy { it.key }.mapValues { (_, v) -> v.maxOf { it.score } }
        val slotRows = stored.filter { !it.isExtra && it.slotKey != null }
        val chosen = slotRows.filter { it.slotKey in scores }
            .sortedWith(compareByDescending<ExtractedData> { it.confidence >= ConfidenceCombiner.REVIEW_BELOW }.thenByDescending { scores[it.slotKey] })
            .take(MAX_KEY_SLOTS).map { it.id }.toSet()
        return slotRows.mapNotNull { row ->
            val importance = if (row.id in chosen) scores[row.slotKey] else null
            row.takeIf { it.importance != importance }?.copy(importance = importance)
        }
    }
}
