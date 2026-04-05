package com.postsaimanager.core.ai.arabiya

/**
 * Mappings and inference logic ported from arabiya/parser_hrm.py.
 *
 * Translates the HRM model's integer outputs into
 * human-readable UD (Universal Dependencies) format strings.
 */
object HrmMappings {

    // ═══════════════════════════════════════
    //  Case class IDs → UD case tags
    // ═══════════════════════════════════════
    val CASE_CLASSES = mapOf(
        0 to null,     // None/Indeclinable
        1 to "Nom",    // Nominative  مرفوع
        2 to "Acc",    // Accusative  منصوب
        3 to "Gen",    // Genitive    مجرور
        4 to "Jus"     // Jussive     مجزوم (for verbs, extended from v2)
    )

    // ═══════════════════════════════════════
    //  Dependency relation IDs → UD strings
    // ═══════════════════════════════════════
    val DEP_RELS_REVERSE = mapOf(
        0 to "_",
        1 to "root",
        2 to "nsubj",
        3 to "obj",
        4 to "iobj",
        5 to "obl",
        6 to "advmod",
        7 to "amod",
        8 to "nmod",
        9 to "det",
        10 to "case",
        11 to "conj",
        12 to "cc",
        13 to "punct",
        14 to "flat",
        15 to "compound",
        16 to "appos",
        17 to "acl",
        18 to "advcl",
        19 to "cop",
        20 to "mark",
        21 to "dep",
        22 to "parataxis",
        23 to "fixed",
        24 to "vocative",
        25 to "nummod",
        26 to "flat:foreign",
        27 to "nsubj:pass",
        28 to "csubj",
        29 to "xcomp",
        30 to "ccomp",
        31 to "orphan"
    )

    // ═══════════════════════════════════════
    //  POS Inference from syntactic context
    // ═══════════════════════════════════════

    private val COMMON_VERBS = setOf(
        "كان", "قال", "كتب", "ذهب", "جعل", "علم", "وجد", "أخذ", "ترك",
        "بدأ", "رأى", "جاء", "أصبح", "ظل", "بات", "صار", "ليس", "مازال",
        "وضع", "أعلن", "أكد", "طلب", "حقق", "أضاف", "أشار", "عاد",
        "دعا", "قرر", "أوضح", "اعتبر", "قدم", "حصل", "نفى", "أجرى",
        "وصل", "خرج", "دخل", "فتح", "أكل", "شرب", "درس", "فهم", "قرأ",
        "سمع", "رجع", "نزل", "ركب", "سأل", "أراد", "حمل", "وقع",
    )

    private val VERB_PREFIXES = setOf('ي', 'ت', 'ن', 'أ')

    private val PARTICLES = setOf(
        "إن", "أن", "لا", "لم", "لن", "ما", "هل", "قد", "سوف",
        "إذا", "لو", "كي", "حتى", "يا",
    )

    private val PREPS = setOf(
        "إلى", "في", "من", "على", "عن", "حتى", "منذ", "خلال", "بين", "عبر"
    )

    private val CONJ = setOf("و", "أو", "ثم", "ف", "لكن", "بل", "أم")

    private val PRONS = setOf(
        "هو", "هي", "هم", "هن", "أنا", "نحن", "أنت", "أنتم",
        "الذي", "التي", "الذين", "هذا", "هذه", "ذلك", "تلك"
    )

    /**
     * Infer POS from dependency relation, case tag, and word morphology.
     * Ported from HRMParserAdapter._infer_pos_from_relation()
     *
     * Key insight: case_tag=None from the model strongly signals
     * indeclinable words (verbs, particles, prepositions).
     */
    fun inferPosFromRelation(word: String, relation: String, caseTag: String?): String {
        // Closed-class words from relation
        return when (relation) {
            "case" -> "ADP"
            "cc" -> "CCONJ"
            "mark" -> "SCONJ"
            "det" -> "DET"
            "punct" -> "PUNCT"
            "nummod" -> "NUM"
            else -> {
                // Particles
                if (word in PARTICLES) return "PART"
                if (word in PREPS) return "ADP"
                if (word in CONJ) return "CCONJ"
                if (word in PRONS) return "PRON"

                // Root relation
                if (relation == "root") {
                    return "VERB" // Root is almost always a verb in Arabic
                }

                // Verb detection from morphology
                if (looksLikeVerb(word)) return "VERB"

                // Subject/object
                when (relation) {
                    "nsubj", "nsubj:pass", "obj", "iobj", "obl", "nmod" -> "NOUN"
                    "amod" -> "ADJ"
                    "advmod" -> "ADV"
                    "conj", "appos" -> "NOUN"
                    "xcomp", "ccomp", "advcl", "acl" -> "VERB"
                    else -> "NOUN" // Fallback
                }
            }
        }
    }

    /**
     * Heuristic: does this bare word look like a verb?
     */
    private fun looksLikeVerb(word: String): Boolean {
        if (word.isEmpty()) return false
        // Known common verbs
        if (word in COMMON_VERBS) return true
        // Imperfective prefix (يكتب، تكتب، نكتب، أكتب)
        if (word.length >= 3 && word[0] in VERB_PREFIXES && !word.startsWith("ال")) return true
        // Past tense pattern: 3-letter no-prefix no-ال
        if (word.length == 3 && !word.startsWith("ال")) return true
        return false
    }

    /**
     * Infer morphological features from word form.
     */
    fun inferFeatures(word: String, relation: String, pos: String): Map<String, String> {
        val feat = mutableMapOf<String, String>()

        // Definiteness
        feat["definite"] = if (ArabicConstants.hasDefiniteArticle(word)) "yes" else "no"

        // Gender
        feat["gender"] = if (word.endsWith("ة") || word.endsWith("ات")) "fem" else "masc"

        // Number and plural type
        when {
            word.endsWith("ون") || word.endsWith("ين") -> {
                feat["number"] = "plur"
                feat["plural_type"] = "sound_masc"
            }
            word.endsWith("ات") -> {
                feat["number"] = "plur"
                feat["gender"] = "fem"
                feat["plural_type"] = "sound_fem"
            }
            else -> {
                feat["number"] = "sing"
            }
        }

        // Construct state
        if (relation in setOf("nmod", "flat", "compound")) {
            feat["construct"] = "yes"
        }

        // Verb form
        if (pos == "VERB") {
            val bare = ArabicConstants.stripDiacritics(word)
            feat["verb_form"] = when {
                bare.length >= 3 && bare[0] in VERB_PREFIXES && !bare.startsWith("ال") -> "pres"
                else -> "past"
            }
        }

        return feat
    }
}
