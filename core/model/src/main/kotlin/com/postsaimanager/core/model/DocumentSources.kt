package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/** Who chose a document's family and topics. Extraction replaces them only while this is [MODEL]. */
@Serializable
enum class FamilySource {
    MODEL,
    USER,
    ;

    companion object {
        fun parse(name: String?): FamilySource = entries.firstOrNull { it.name == name } ?: MODEL
    }
}

/** Where a document's title came from. [USER] is never replaced; see `DocumentTitlePolicy`. */
@Serializable
enum class TitleSource {
    /** An app default ("Scanned N pages"), rendered from a code. */
    DEFAULT,

    /** Composed from the family, the sender and the subject. */
    COMPOSED,

    /** Words the model wrote (an older extractor). */
    MODEL,

    /** A person renamed the document. */
    USER,
    ;

    companion object {
        fun parse(name: String?): TitleSource? = entries.firstOrNull { it.name == name }
    }
}

/** Where a document's summary came from. Extraction replaces it only while this is not [USER]. */
@Serializable
enum class SummarySource {
    MODEL,

    /** Rendered from string resources out of the verified fields. */
    TEMPLATE,
    USER,
    ;

    companion object {
        fun parse(name: String?): SummarySource? = entries.firstOrNull { it.name == name }
    }
}
