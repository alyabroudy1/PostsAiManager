package com.postsaimanager.core.domain.document

/**
 * When the title the model wrote may replace a document's stored title.
 *
 * A document starts with a default title the app wrote (`scanned_pages`, stored with a code). The
 * model's title replaces that, and replaces whatever title a document carries when the model reads it
 * for the first time. After that the title is settled: a reprocess (a newer extractor, a manual
 * "read again") must not rename a document the person may already have found by its title. A title a
 * person set is never replaced.
 */
object DocumentTitlePolicy {

    /**
     * @param isUserTitle a person chose the current title
     * @param titleCode the code of a default title still in place, or null once the title is real words
     * @param modelHasRead a model has read this document before (its type was stored), so a model title
     *   already exists or was deliberately not taken
     */
    fun modelTitleMayReplace(isUserTitle: Boolean, titleCode: String?, modelHasRead: Boolean): Boolean =
        !isUserTitle && (titleCode != null || !modelHasRead)
}
