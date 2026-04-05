package com.postsaimanager.core.ai.arabiya

/**
 * Core Arabic Unicode constants and utility functions.
 * Ported from arabiya/core.py — the foundation of all Arabic character manipulation.
 *
 * CRITICAL: Arabic diacritics are Unicode COMBINING CHARACTERS.
 * They appear AFTER the base letter: "بُ" = U+0628 (baa) + U+064F (damma).
 */
object ArabicConstants {
    // Diacritical marks (tashkeel)
    const val FATHATAN  = '\u064B'  // ً  — tanween fath
    const val DAMMATAN  = '\u064C'  // ٌ  — tanween damm
    const val KASRATAN  = '\u064D'  // ٍ  — tanween kasr
    const val FATHA     = '\u064E'  // َ  — fatha
    const val DAMMA     = '\u064F'  // ُ  — damma
    const val KASRA     = '\u0650'  // ِ  — kasra
    const val SHADDA    = '\u0651'  // ّ  — shadda (gemination)
    const val SUKUN     = '\u0652'  // ْ  — sukun (no vowel)

    // Special characters
    const val ALEF_MAQSURA = '\u0649'  // ى
    const val TAA_MARBUTA  = '\u0629'  // ة

    /** All case-related diacritics */
    val CASE_DIACRITICS = setOf(
        FATHATAN, DAMMATAN, KASRATAN,
        FATHA, DAMMA, KASRA, SUKUN
    )

    /** All diacritics pattern for stripping */
    private val DIACRITIC_REGEX = Regex("[\u0617-\u061A\u064B-\u0655\u0670]")

    fun stripDiacritics(text: String): String = DIACRITIC_REGEX.replace(text, "")

    fun isArabicLetter(char: Char): Boolean {
        val cp = char.code
        return (cp in 0x0621..0x064A) || cp == 0x0671
    }

    fun isDiacritic(char: Char): Boolean {
        val cp = char.code
        return cp in 0x064B..0x0655 || cp == 0x0670 || cp in 0x0617..0x061A
    }

    fun hasDefiniteArticle(word: String): Boolean {
        val bare = stripDiacritics(word)
        return bare.startsWith("ال") && bare.length > 2
    }

    fun endsWithTaaMarbuta(word: String): Boolean {
        val bare = stripDiacritics(word)
        return bare.isNotEmpty() && bare.last() == TAA_MARBUTA
    }

    fun endsWithAlefMaqsura(word: String): Boolean {
        val bare = stripDiacritics(word)
        return bare.isNotEmpty() && bare.last() == ALEF_MAQSURA
    }

    /**
     * Decompose an Arabic word into (base_letter, [diacritics]) pairs.
     */
    fun decomposeWord(word: String): List<Pair<Char, MutableList<Char>>> {
        val result = mutableListOf<Pair<Char, MutableList<Char>>>()
        var currentLetter: Char? = null
        var currentDiacritics = mutableListOf<Char>()

        for (char in word) {
            when {
                isArabicLetter(char) -> {
                    currentLetter?.let {
                        result.add(it to currentDiacritics)
                    }
                    currentLetter = char
                    currentDiacritics = mutableListOf()
                }
                isDiacritic(char) -> {
                    currentDiacritics.add(char)
                }
                else -> {
                    currentLetter?.let {
                        result.add(it to currentDiacritics)
                        currentLetter = null
                        currentDiacritics = mutableListOf()
                    }
                    result.add(char to mutableListOf())
                }
            }
        }
        currentLetter?.let {
            result.add(it to currentDiacritics)
        }
        return result
    }

    /**
     * Recompose a decomposed word back into a string.
     */
    fun recomposeWord(decomposed: List<Pair<Char, List<Char>>>): String {
        return buildString {
            for ((letter, diacritics) in decomposed) {
                append(letter)
                diacritics.forEach { append(it) }
            }
        }
    }

    /**
     * Find the index of the last Arabic letter in a decomposed word.
     */
    fun getLastLetterIndex(decomposed: List<Pair<Char, MutableList<Char>>>): Int {
        for (i in decomposed.indices.reversed()) {
            if (isArabicLetter(decomposed[i].first)) {
                return i
            }
        }
        return -1
    }

    /**
     * Replace the case diacritic on the last Arabic letter. Preserves SHADDA.
     * Ported from arabiya/core.py replace_case_ending()
     */
    fun replaceCaseEnding(word: String, newDiacritic: Char): String {
        if (word.isEmpty()) return word

        val decomposed = decomposeWord(word).map { (ch, diacs) -> ch to diacs.toMutableList() }
        val lastIdx = getLastLetterIndex(decomposed)
        if (lastIdx < 0) return word

        val (letter, existing) = decomposed[lastIdx]
        val preserved = existing.filter { it !in CASE_DIACRITICS }.toMutableList()

        val newMarks = mutableListOf<Char>()
        if (SHADDA in preserved) {
            newMarks.add(SHADDA)
            preserved.remove(SHADDA)
        }
        newMarks.addAll(preserved)
        newMarks.add(newDiacritic)

        val mutableDecomposed = decomposed.toMutableList()
        mutableDecomposed[lastIdx] = letter to newMarks
        return recomposeWord(mutableDecomposed)
    }
}
