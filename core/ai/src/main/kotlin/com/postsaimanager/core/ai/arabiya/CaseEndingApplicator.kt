package com.postsaimanager.core.ai.arabiya

import com.postsaimanager.core.ai.arabiya.ArabicConstants.DAMMA
import com.postsaimanager.core.ai.arabiya.ArabicConstants.DAMMATAN
import com.postsaimanager.core.ai.arabiya.ArabicConstants.FATHA
import com.postsaimanager.core.ai.arabiya.ArabicConstants.FATHATAN
import com.postsaimanager.core.ai.arabiya.ArabicConstants.KASRA
import com.postsaimanager.core.ai.arabiya.ArabicConstants.KASRATAN
import com.postsaimanager.core.ai.arabiya.ArabicConstants.SUKUN

/**
 * Applies case endings (iʻrāb) based on syntactic analysis.
 * Ported from arabiya/diacritizer.py CaseEndingApplicator.
 *
 * Handles all Arabic grammar special cases:
 * - Tanween for indefinite nouns
 * - Sound masculine plural (final noon gets fatha)
 * - Sound feminine plural (acc/gen both use kasra — exception!)
 * - Dual (kasra on noon)
 * - Alef maqsura (hidden case)
 * - Verb mood markings
 */
object CaseEndingApplicator {

    /**
     * Data class holding the morphological context for case ending determination.
     */
    data class WordContext(
        val caseName: String?,        // UD: "Nom", "Acc", "Gen", "Jus", or null
        val pos: String,              // UD POS tag
        val bareWord: String,         // Word without diacritics
        val isDefinite: Boolean,
        val isConstruct: Boolean = false,
        val number: String = "sing",  // sing, dual, plur
        val gender: String = "masc",  // masc, fem
        val pluralType: String = "",  // sound_masc, sound_fem, broken, ""
        val verbForm: String = ""     // past, pres, imp, ""
    )

    /**
     * Returns the Unicode diacritic character for the case ending,
     * or empty char if indeclinable.
     */
    fun getCaseDiacritic(ctx: WordContext): Char? {
        val case = ctx.caseName ?: return null

        // Particles, prepositions, pronouns — always indeclinable
        if (ctx.pos in INDECLINABLE_POS) return null

        // Verbs
        if (ctx.pos in setOf("VERB", "AUX")) {
            return getVerbCase(ctx)
        }

        // Nouns, Adjectives, Proper Nouns
        return getNounCase(ctx)
    }

    private fun getNounCase(ctx: WordContext): Char? {
        val case = ctx.caseName ?: return null

        // Sound masculine plural — final noon gets FATHA
        if (ctx.pluralType == "sound_masc") {
            return FATHA
        }

        // Sound feminine plural — Acc uses KASRA (exception!)
        if (ctx.pluralType == "sound_fem") {
            val useTanween = !ctx.isDefinite && !ctx.isConstruct
            return when (case) {
                "Nom" -> if (useTanween) DAMMATAN else DAMMA
                else -> if (useTanween) KASRATAN else KASRA  // Acc and Gen both use kasra
            }
        }

        // Dual — final noon gets KASRA
        if (ctx.number == "dual") {
            return KASRA
        }

        // Alef maqsura — hidden case
        if (ArabicConstants.endsWithAlefMaqsura(ctx.bareWord)) {
            return null
        }

        // Regular/broken plural/singular
        return getStandardCase(case, ctx.isDefinite, ctx.isConstruct)
    }

    private fun getStandardCase(case: String, isDefinite: Boolean, isConstruct: Boolean): Char? {
        val useTanween = !isDefinite && !isConstruct
        return when (case) {
            "Nom" -> if (useTanween) DAMMATAN else DAMMA
            "Acc" -> if (useTanween) FATHATAN else FATHA
            "Gen" -> if (useTanween) KASRATAN else KASRA
            "Jus" -> SUKUN
            else -> null
        }
    }

    private fun getVerbCase(ctx: WordContext): Char? {
        val vf = ctx.verbForm
        val case = ctx.caseName ?: return null

        // Past and imperative verbs are indeclinable
        if (vf in setOf("past", "imp")) return null

        return when (case) {
            "Nom" -> DAMMA
            "Acc" -> FATHA
            "Jus" -> SUKUN
            else -> null
        }
    }

    private val INDECLINABLE_POS = setOf(
        "ADP", "CCONJ", "SCONJ", "PART", "DET", "INTJ", "PRON"
    )
}

/**
 * Combines stem diacritics with case endings.
 * Ported from arabiya/diacritizer.py DiacriticCombiner.
 */
object DiacriticCombiner {

    data class CombineResult(
        val finalDiacritized: String,
        val caseDiacritic: String   // The applied Unicode char as a string
    )

    /**
     * Combines a stem-diacritized word with a case ending diacritic.
     */
    fun combine(
        stemDiacritized: String,
        bareWord: String,
        caseDiacriticChar: Char?
    ): CombineResult {
        val base = stemDiacritized.ifBlank { bareWord }

        return if (caseDiacriticChar != null) {
            val final = ArabicConstants.replaceCaseEnding(base, caseDiacriticChar)
            CombineResult(final, caseDiacriticChar.toString())
        } else {
            CombineResult(base, "")
        }
    }
}
