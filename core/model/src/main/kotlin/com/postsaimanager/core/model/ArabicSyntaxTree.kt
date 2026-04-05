package com.postsaimanager.core.model

/**
 * Represents a parsed Arabic sentence with deep morphological and
 * syntactic dependency structures predicted by the HRM-Grid ONNX Model.
 */
data class ArabicSyntaxTree(
    val sentence: String,
    val nodes: List<SyntaxNode>
)

/**
 * A single token/word in the syntax tree alongside its extracted properties.
 */
data class SyntaxNode(
    val id: Int,                     // 1-indexed (0 is for Root)
    val word: String,                // Original word segment
    val headId: Int,                 // The ID of the head word (0 = Root)
    val relationKey: Int,            // The generic predicted DepRel int id limit
    val caseKey: Int,                // I'rab case key (1=Nom, 2=Acc, 3=Gen, etc.)
    val isRoot: Boolean = false
) {
    /**
     * Helper to map raw numeric relation categories to human-readable dependency names.
     * Note: This maps to the 50 relations trained in the V2 model.
     */
    fun getRelationName(): String {
        // Fallback simplified mapping; typical UD Arabic relations
        return when (relationKey) {
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

    /**
     * Iʻrāb case map based on the V2 model config (n_cases = 5)
     */
    fun getCaseName(): String {
        return when (caseKey) {
            0 -> "None/Indeclinable"
            1 -> "Nominative (مرفوع)"
            2 -> "Accusative (منصوب)"
            3 -> "Genitive (مجرور)"
            4 -> "Jussive (مجزوم)"
            else -> "Unknown"
        }
    }
}
