package com.postsaimanager.core.domain.reading

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a person is looking at a given document right now: the app is in the foreground AND that document's screen is the one
 * showing. The one owner of the question "would a notification about this document only tell them what they already see?".
 *
 * The app's lifecycle observer reports the foreground ([setAppInForeground]); the document screen reports itself while it is shown
 * ([documentOpened] / [documentClosed], balanced, so two screens of the same document nest).
 */
@Singleton
class ViewingState @Inject constructor() {

    private val lock = Any()
    private var foreground = false
    private val shown = HashMap<String, Int>()

    fun setAppInForeground(inForeground: Boolean) = synchronized(lock) { foreground = inForeground }

    fun documentOpened(documentId: String) = synchronized(lock) { shown[documentId] = (shown[documentId] ?: 0) + 1 }

    fun documentClosed(documentId: String) = synchronized(lock) {
        val left = (shown[documentId] ?: 0) - 1
        if (left > 0) shown[documentId] = left else shown.remove(documentId)
    }

    /** True only while the app is in the foreground and [documentId]'s screen is showing. */
    fun isViewing(documentId: String): Boolean = synchronized(lock) { foreground && (shown[documentId] ?: 0) > 0 }
}
