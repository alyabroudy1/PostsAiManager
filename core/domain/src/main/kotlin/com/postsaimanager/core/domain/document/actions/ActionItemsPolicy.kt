package com.postsaimanager.core.domain.document.actions

import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ActionSource

/**
 * What a re-read may do to a document's actions, the one place that decides it. The model's actions are disposable (every second stage
 * replaces them), a person's are not: an action they added, edited or deleted survives every re-read.
 *
 * Pure: it takes the stored list and the list a reading chose, and returns the list to store.
 */
object ActionItemsPolicy {

    /**
     * The stored actions after a reading that chose [fresh].
     *
     * The person's ([ActionSource.USER]) actions stay as they are, a deletion (a tombstone) included. A model action is not added when it
     * is the one a person already dealt with: the kind of a person's action, or the kind it replaced ([ActionItem.origin]), is taken, so
     * a reading that chooses it again neither duplicates an edited action nor brings a deleted one back. With no action of the person's
     * the reading's list simply replaces the stored one.
     */
    fun merge(stored: List<ActionItem>, fresh: List<ActionItem>): List<ActionItem> {
        val mine = stored.filter { it.source == ActionSource.USER }
        if (mine.isEmpty()) return fresh
        val taken = mine.flatMap { listOfNotNull(it.origin, it.kind) }.toSet()
        return fresh.filter { it.kind !in taken } + mine
    }
}
