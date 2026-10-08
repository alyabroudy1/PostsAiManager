package com.postsaimanager.core.domain.extraction.gemma

import kotlinx.coroutines.flow.Flow

/**
 * Which reader reads the letters: Gemma (the chat model, the default) or the old Qwen zone scorer. The switch is "Reader: Gemma (default) /
 * Qwen scorer (old)" in Settings, Debug (a debug build only); a release build always reads with Gemma. Whichever is chosen, the other is the
 * fallback: Gemma is not installed, busy, failing, too slow or answers something unusable, and the old reading runs, so a document is never
 * left unread.
 *
 * (The name is the trial's from before Gemma became the default; the type is the one owner of the choice.)
 *
 * Two things make a reading Gemma's: the switch (on by default), and a one-off request for one document (the debug action "Read again with
 * Gemma"), which is spent by the reading that takes it and wins over a switch that says "old".
 */
interface GemmaReaderTrial {

    /** The state of the switch: true is Gemma (the default), false the old Qwen scorer. */
    val enabled: Flow<Boolean>

    suspend fun isEnabled(): Boolean

    suspend fun setEnabled(enabled: Boolean)

    /** Asks for [documentId]'s next reading to be Gemma's, whatever the switch says. */
    fun requestOnce(documentId: String)

    /** True once for a document [requestOnce] was called for since its last reading; the request is spent. */
    fun takeRequest(documentId: String): Boolean
}

/** Whether the next reading of [documentId] is Gemma's: a one-off request (spent here) or the switch. */
suspend fun GemmaReaderTrial.shouldRead(documentId: String): Boolean {
    val requested = takeRequest(documentId)
    return requested || isEnabled()
}
