package com.postsaimanager.core.data.gemma

import android.util.Log
import com.postsaimanager.core.domain.document.followup.AfterReadingLog
import javax.inject.Inject
import javax.inject.Singleton

/** [AfterReadingLog] on the Android log, tag `AfterReading`: the document's id, which question and why it stays pending, never a word of the letter. */
@Singleton
class AndroidAfterReadingLog @Inject constructor() : AfterReadingLog {

    override fun pending(documentId: String, question: String, reason: String) {
        Log.w(TAG, "$question for $documentId is pending (asked again on the next reading): $reason")
    }

    private companion object {
        const val TAG = "AfterReading"
    }
}
