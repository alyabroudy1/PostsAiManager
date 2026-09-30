package com.postsaimanager.core.domain.document

/**
 * When the title the model wrote may replace a document's stored title.
 *
 * A document starts with a default title the app wrote (`scanned_pages`, stored with a code). The
 * model's title replaces that, and only that: once the title is real words (an earlier reading's, or
 * one that was already there) it is settled, because the person may already have found the document by
 * it. A reprocess, a newer extractor or a manual "read again" never renames a document, and a title a
 * person set is never replaced.
 */
object DocumentTitlePolicy {

    /**
     * @param isUserTitle a person chose the current title
     * @param titleCode the code of a default title still in place, or null once the title is real words
     */
    fun modelTitleMayReplace(isUserTitle: Boolean, titleCode: String?): Boolean =
        !isUserTitle && titleCode != null
}
