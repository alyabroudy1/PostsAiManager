package com.postsaimanager.core.model

/**
 * Represents a parsed Arabic sentence with deep morphological and
 * syntactic dependency structures predicted by the HRM-Grid ONNX Model,
 * plus full diacritization (tashkeel) from the Arabiya pipeline.
 */
data class ArabicSyntaxTree(
    val sentence: String,
    val diacritizedSentence: String = "",
    val nodes: List<SyntaxNode>
)

/**
 * A single token/word in the syntax tree alongside its extracted properties.
 * Contains the full diacritization pipeline output matching the Python Arabiya engine.
 */
data class SyntaxNode(
    val id: Int,                          // 1-indexed (0 is for Root)
    val word: String,                     // Original bare word segment
    val diacritizedWord: String = "",     // Fully diacritized with tashkeel
    val stemDiacritized: String = "",     // Stem vowels from lexicon
    val caseDiacritic: String = "",       // Unicode case ending diacritic
    val headId: Int,                      // The ID of the head word (0 = Root)
    val posTag: String = "",              // UD POS tag (VERB, NOUN, ADJ, ADP, etc.)
    val relation: String = "",            // UD dependency relation string (nsubj, obj, obl, etc.)
    val caseName: String? = null,         // UD case tag: "Nom", "Acc", "Gen", "Jus", or null
    val caseKey: Int = 0,                 // Raw case class ID from model (0-4)
    val relationKey: Int = 0,             // Raw relation class ID from model
    val isDefinite: Boolean = false,
    val number: String = "sing",          // sing, dual, plur
    val gender: String = "masc",          // masc, fem
    val isRoot: Boolean = false,
    val diacSource: String = "none",      // lexicon, passthrough, none
    val confidence: Float = 0f
) {
    /**
     * Arabic display label for the case.
     */
    fun getCaseDisplayArabic(): String {
        return when (caseName) {
            "Nom" -> "مرفوع"
            "Acc" -> "منصوب"
            "Gen" -> "مجرور"
            "Jus" -> "مجزوم"
            null  -> "مبني"
            else  -> "مبني"
        }
    }

    /**
     * English case display name.
     */
    fun getCaseDisplayEnglish(): String {
        return when (caseName) {
            "Nom" -> "Nominative (مرفوع)"
            "Acc" -> "Accusative (منصوب)"
            "Gen" -> "Genitive (مجرور)"
            "Jus" -> "Jussive (مجزوم)"
            null  -> "Indeclinable (مبني)"
            else  -> "Unknown"
        }
    }

    /**
     * Human-readable relation name.
     */
    fun getRelationName(): String {
        return relation.ifBlank {
            when (relationKey) {
                0 -> "root"
                1 -> "nsubj"
                2 -> "obj"
                3 -> "obl"
                4 -> "amod"
                5 -> "nmod"
                6 -> "cc"
                7 -> "conj"
                8 -> "mark"
                9 -> "advmod"
                else -> "dep_$relationKey"
            }
        }
    }

    /**
     * The display word: prefer diacritized, fall back to bare.
     */
    fun getDisplayWord(): String {
        return diacritizedWord.ifBlank { word }
    }
}
