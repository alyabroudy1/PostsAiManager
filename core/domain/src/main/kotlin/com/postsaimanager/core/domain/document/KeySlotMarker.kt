package com.postsaimanager.core.domain.document

import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.KeySlot

/**
 * Applies what a second stage picked as key information to the stored slot rows: a picked slot's row gets the score as its
 * [ExtractedData.importance], every other slot row loses a pick an earlier reading gave it (the newest reading decides). Extras are not
 * touched (they are key information by being shown). Pure; the caller persists the rows it returns.
 */
object KeySlotMarker {

    /**
     * The stored rows whose importance changes, already updated. Empty when [picked] is null (the stage did not score the slots, so what
     * is stored stays).
     */
    fun mark(stored: List<ExtractedData>, picked: List<KeySlot>?): List<ExtractedData> {
        if (picked == null) return emptyList()
        val scores = picked.groupBy { it.key }.mapValues { (_, v) -> v.maxOf { it.score } }
        return stored.filter { !it.isExtra && it.slotKey != null }.mapNotNull { row ->
            val importance = scores[row.slotKey]
            row.takeIf { it.importance != importance }?.copy(importance = importance)
        }
    }
}
