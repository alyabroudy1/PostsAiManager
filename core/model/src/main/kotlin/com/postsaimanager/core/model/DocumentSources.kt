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

/** Who wrote the recognised text of a page. A re-read never replaces text that is [USER]'s. */
@Serializable
enum class PageTextSource {
    OCR,
    USER,
    ;

    companion object {
        fun parse(name: String?): PageTextSource = entries.firstOrNull { it.name == name } ?: OCR
    }
}

/** Who decided which household people a document is for or about. The people check replaces the list only while this is [MODEL]. */
@Serializable
enum class ConcernedSource {
    MODEL,
    USER,
    ;

    companion object {
        fun parse(name: String?): ConcernedSource = entries.firstOrNull { it.name == name } ?: MODEL
    }
}

/** Who decided a document's language. A re-read replaces it only while this is [MODEL]. */
@Serializable
enum class LanguageSource {
    MODEL,
    USER,
    ;

    companion object {
        fun parse(name: String?): LanguageSource = entries.firstOrNull { it.name == name } ?: MODEL
    }
}

/** Who chose the matter a letter belongs to ("no matter" included). A re-read regroups the letter only while this is [AUTO]. */
@Serializable
enum class CaseLinkSource {
    AUTO,
    USER,
    ;

    companion object {
        fun parse(name: String?): CaseLinkSource = entries.firstOrNull { it.name == name } ?: AUTO
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

    /**
     * The model's first sentences about the document, shown at once but not through every check (the early summary a reading's first
     * turn writes). The text step that follows replaces it with a verified one; it stays when that step cannot write one.
     */
    MODEL_TO_CHECK,

    /** Rendered from string resources out of the verified fields. */
    TEMPLATE,
    USER,
    ;

    companion object {
        fun parse(name: String?): SummarySource? = entries.firstOrNull { it.name == name }
    }
}
