package com.postsaimanager.core.domain.reading

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Letters understood within a short time of each other belong to one notification ("3 letters understood"). A letter joins the batch
 * while the batch's notification is still showing and the last letter was added less than [windowMillis] ago (one letter takes a few
 * minutes to read, so the window is longer than that); otherwise it starts a new batch, so a notification a person dismissed or opened
 * never comes back with old letters in it.
 *
 * In memory on purpose: after the process dies the next letter simply starts a new notification.
 */
@Singleton
class ReadingFinishedBatch(private val windowMillis: Long) {

    @Inject
    constructor() : this(DEFAULT_WINDOW_MILLIS)

    private val lock = Any()
    private val letters = ArrayList<UnderstoodLetter>()
    private var lastAddedAt = 0L

    /**
     * Adds [letter] (replacing the same letter if it is already in the batch) and returns the batch.
     *
     * @param stillShown the batch's notification is still in the shade
     */
    fun add(letter: UnderstoodLetter, now: Long, stillShown: Boolean): List<UnderstoodLetter> = synchronized(lock) {
        if (!stillShown || now - lastAddedAt > windowMillis) letters.clear()
        letters.removeAll { it.documentId == letter.documentId }
        letters += letter
        lastAddedAt = now
        letters.toList()
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 10 * 60 * 1000L
    }
}
