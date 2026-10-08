package com.postsaimanager.core.domain.extraction.gemma

import kotlinx.coroutines.flow.Flow

/**
 * The switch of the "Gemma reads the letter" trial. Off by default, in debug and release builds alike; only a debug build offers a way to
 * turn it on (a switch in Settings and a "Read again with Gemma (trial)" action on a document). While it is off the reading is exactly what
 * it was before the trial existed.
 *
 * Two things turn the trial on for a reading: the switch (every new reading while it is on), and a one-off request for one document
 * (the debug action), which is spent by the reading that takes it.
 */
interface GemmaReaderTrial {

    /** The state of the switch. */
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
