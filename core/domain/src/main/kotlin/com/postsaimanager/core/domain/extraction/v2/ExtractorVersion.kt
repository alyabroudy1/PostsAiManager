package com.postsaimanager.core.domain.extraction.v2

/**
 * The single source of truth for which extractor wrote a document's machine values
 * (`documents.extractorVersion`).
 *
 * **Bump [CURRENT] deliberately** — when the prompt, the grammar, the schema, the candidate finders or
 * the field mapping change in a way that would read an old letter better. On the next start every
 * finished letter written by an older version is re-read in the background
 * (`ReprocessOutdatedDocumentsUseCase`), without touching anything a person confirmed or edited.
 *
 * Versions read `extraction-v2-<n>`; a higher `n` is newer. The other two ids are what the pipeline
 * records when the model did not read the letter, and they are not on that scale:
 * - [FOUND_VALUES] is a run with no model, which is re-derived as soon as one is installed by the
 *   ordinary fingerprint logic, and re-reading it with no model would only produce it again;
 * - `entity-extractor-1` is the stamp of the pre-v2 pattern extractor, which no longer exists. Nothing
 *   writes it now, but documents stored with it remain, and [isOutdated] treats it (like any stamp that
 *   is not on the model scale) as older than every model version.
 */
object ExtractorVersion {
    /** What a model-read document is stamped with today. */
    const val CURRENT = "extraction-v2-11"

    /**
     * The first version whose second stage picks the extras by what the family's hint says the reader needs (the "Key information") and
     * writes the action lines. A document stamped with an earlier one has extras picked without that guidance and no action lines, so the
     * Extracted tab keeps them under "All details" until the background re-read.
     */
    private const val KEY_INFO_FROM = 3

    /** Whether the extras of a document stamped [stored] are the hint-guided key information. */
    fun readsKeyInfo(stored: String?): Boolean = stored != null && (number(stored) ?: 0) >= KEY_INFO_FROM

    /** No model read the document: only values found by code. */
    const val FOUND_VALUES = "found-values-1"

    private const val MODEL_PREFIX = "extraction-v2-"
    private const val FOUND_PREFIX = "found-values-"

    /** Whether a document stamped [stored] should be re-read now that the extractor is [CURRENT]. */
    fun isOutdated(stored: String?, current: String = CURRENT): Boolean {
        if (stored == null) return true
        if (stored == current) return false
        // Never on the model scale, and re-reading without a model cannot improve it (see the class KDoc).
        if (stored.startsWith(FOUND_PREFIX)) return false
        val storedNumber = number(stored)
        val currentNumber = number(current)
        // A pattern-extractor stamp, or anything unrecognised, is older than any model version.
        if (storedNumber == null || currentNumber == null) return true
        return storedNumber < currentNumber
    }

    /**
     * Whether a document stamped [stored] was read without the model (only values found by code), so it is owed a real reading once
     * the model is installed. Not [isOutdated]: re-reading it without a model would only produce the same stamp again.
     */
    fun awaitsModel(stored: String?): Boolean = stored?.startsWith(FOUND_PREFIX) == true

    private fun number(version: String): Int? =
        version.removePrefix(MODEL_PREFIX).takeIf { version.startsWith(MODEL_PREFIX) }?.toIntOrNull()
}
