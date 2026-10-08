package com.postsaimanager.core.domain.reading

/**
 * The port that puts a "Letter understood" notification on the device (`:core:data` implements it; it owns the channel, the words and
 * the permission). It decides nothing about whether to notify or what may be said: [AnnounceUnderstoodLetterUseCase] does.
 */
interface ReadingFinishedNotifier {

    /** Whether the notification of the current batch is still in the shade. */
    fun isShowing(): Boolean

    /**
     * Posts, or updates in place, the one notification for [content]. Does nothing without the notification permission (it is never
     * asked for here). Returns whether a notification was posted.
     */
    fun show(content: ReadingFinishedContent): Boolean
}
