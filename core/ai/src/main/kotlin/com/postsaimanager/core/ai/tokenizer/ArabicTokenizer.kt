package com.postsaimanager.core.ai.tokenizer

import com.postsaimanager.core.ai.arabiya.ArabicConstants
import java.security.MessageDigest

/**
 * Bridges raw Arabic strings into the dense Long arrays
 * required by the HRM-Grid V2 ONNX parser.
 *
 * Uses the EXACT SAME hash functions as the Python training pipeline
 * (arabiya/parser_hrm.py) to ensure model input compatibility.
 */
class ArabicTokenizer {

    companion object {
        const val MAX_WORDS = 32
        const val MAX_CHARS = 16
        const val MAX_SUBWORDS = 4

        // Vocab sizes matching ParserConfig from the Python training
        private const val WORD_VOCAB = 10000
        private const val POS_VOCAB = 20
        private const val CHAR_VOCAB = 300
        private const val BPE_VOCAB = 8000
        private const val ROOT_VOCAB = 5000
        private const val PATTERN_VOCAB = 200
        private const val PROCLITIC_VOCAB = 200
        private const val ENCLITIC_VOCAB = 100
        private const val DIAC_VOCAB = 20
    }

    private val rootExtractor = ArabicRootExtractor()

    data class TokenizedTensors(
        val words: List<String>,
        val wordIds: LongArray,        // [1, 32]
        val posTags: LongArray,        // [1, 32]
        val charIds: LongArray,        // [1, 32, 16] - flattened
        val bpeIds: LongArray,         // [1, 32, 4]  - flattened
        val rootIds: LongArray,        // [1, 32]
        val patternIds: LongArray,     // [1, 32]
        val procliticIds: LongArray,   // [1, 32]
        val encliticIds: LongArray,    // [1, 32]
        val diacIds: LongArray,        // [1, 32, 16] - flattened
        val mask: LongArray            // [1, 32]
    )

    fun tokenizeAndMap(text: String): TokenizedTensors {
        val rawWords = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val words = mutableListOf("<ROOT>")
        words.addAll(rawWords)

        val validWords = words.take(MAX_WORDS).toList()

        val wordIds = LongArray(MAX_WORDS) { 0L }
        val posTags = LongArray(MAX_WORDS) { 0L }
        val charIds = LongArray(MAX_WORDS * MAX_CHARS) { 0L }
        val bpeIds = LongArray(MAX_WORDS * MAX_SUBWORDS) { 0L }
        val rootIds = LongArray(MAX_WORDS) { 0L }
        val patternIds = LongArray(MAX_WORDS) { 0L }
        val procliticIds = LongArray(MAX_WORDS) { 0L }
        val encliticIds = LongArray(MAX_WORDS) { 0L }
        val diacIds = LongArray(MAX_WORDS * MAX_CHARS) { 0L }
        val mask = LongArray(MAX_WORDS) { 0L }

        for (w in validWords.indices) {
            mask[w] = 1L
            if (w == 0) continue // <ROOT> stays zero

            val wordStr = validWords[w]

            // Word ID — same hash as Python grid builder: word_to_bucket()
            val wId = (wordToBucket(wordStr, 8192) % WORD_VOCAB).toLong().coerceAtLeast(1)
            wordIds[w] = wId

            // POS — heuristic guess matching Python _guess_pos()
            posTags[w] = guessPos(wordStr).toLong()

            // Root extraction — same algorithm as Python ArabicRootExtractor
            val root = rootExtractor.extractRoot(wordStr)
            rootIds[w] = stableHash(root, ROOT_VOCAB).toLong()

            // Pattern — stable hash
            patternIds[w] = stableHash(wordStr, PATTERN_VOCAB).toLong()

            // Proclitic/Enclitic (approximated from word_id like Python)
            procliticIds[w] = wId % PROCLITIC_VOCAB
            encliticIds[w] = wId % ENCLITIC_VOCAB

            // Char IDs — direct Unicode ordinals mod CHAR_VOCAB
            val charLen = minOf(wordStr.length, MAX_CHARS)
            for (c in 0 until charLen) {
                val flatIndex = w * MAX_CHARS + c
                val cId = wordStr[c].code.toLong()
                charIds[flatIndex] = (cId % CHAR_VOCAB).coerceAtLeast(1)
                diacIds[flatIndex] = cId % DIAC_VOCAB
            }

            // BPE IDs — stable MD5 hash matching Python
            val bpeHash = stableHash(wordStr, BPE_VOCAB)
            for (s in 0 until MAX_SUBWORDS) {
                bpeIds[w * MAX_SUBWORDS + s] = bpeHash.toLong()
            }
        }

        return TokenizedTensors(
            words = validWords,
            wordIds = wordIds,
            posTags = posTags,
            charIds = charIds,
            bpeIds = bpeIds,
            rootIds = rootIds,
            patternIds = patternIds,
            procliticIds = procliticIds,
            encliticIds = encliticIds,
            diacIds = diacIds,
            mask = mask
        )
    }

    // ═══════════════════════════════════════
    //  Hash functions — MUST match Python exactly
    // ═══════════════════════════════════════

    /**
     * Same hash as used in the Python grid builder:
     * h = (h * 31 + ord(ch)) % num_buckets
     */
    private fun wordToBucket(word: String, numBuckets: Int = 8192): Int {
        var h = 0
        for (ch in word) {
            h = ((h * 31) + ch.code) % numBuckets
        }
        return maxOf(1, h)
    }

    /**
     * Stable MD5-based hash matching Python stable_hash():
     * int(hashlib.md5(s.encode('utf-8')).hexdigest(), 16) % bins
     */
    private fun stableHash(s: String, bins: Int): Int {
        if (s.isEmpty()) return 0
        val md5 = MessageDigest.getInstance("MD5")
        val digest = md5.digest(s.toByteArray(Charsets.UTF_8))
        // Take first 8 bytes to avoid overflow, convert to positive long
        var hash = 0L
        for (i in 0 until minOf(8, digest.size)) {
            hash = (hash shl 8) or (digest[i].toLong() and 0xFF)
        }
        return (Math.abs(hash) % bins).toInt()
    }

    // ═══════════════════════════════════════
    //  POS heuristic — matching Python _guess_pos()
    // ═══════════════════════════════════════

    private val PREPS = setOf("إلى", "في", "من", "على", "عن", "حتى", "منذ", "خلال", "بين", "عبر")
    private val CONJ = setOf("و", "أو", "ثم", "ف", "لكن", "بل", "أم")
    private val PARTS = setOf("لا", "لم", "لن", "ما", "هل", "أ", "قد", "إن", "أن", "سوف")
    private val PRONS = setOf(
        "هو", "هي", "هم", "هن", "أنا", "نحن", "أنت", "أنتم",
        "الذي", "التي", "الذين", "هذا", "هذه", "ذلك", "تلك"
    )

    // POS integer IDs matching the Python POS_TAGS dict
    private val POS_ADP = 5
    private val POS_CCONJ = 6
    private val POS_PART = 8
    private val POS_PRON = 10
    private val POS_NOUN = 1  // Default

    private fun guessPos(word: String): Int {
        if (word in PREPS) return POS_ADP
        if (word in CONJ) return POS_CCONJ
        if (word in PARTS) return POS_PART
        if (word in PRONS) return POS_PRON
        return POS_NOUN // Most common in Arabic
    }
}

/**
 * Lightweight algorithmic Arabic root extractor.
 * Ported from arabiya/parser_hrm.py ArabicRootExtractor.
 *
 * Strips common prefixes/suffixes, then extracts the first 3 consonants.
 */
class ArabicRootExtractor {

    private val PREFIXES = listOf(
        "وال", "فال", "بال", "كال", "لل",
        "وب", "ول", "وي", "فب", "فل", "في",
        "ال", "لي", "لن", "لا", "ست", "سي", "سن", "سأ", "سـ",
        "و", "ف", "ب", "ل", "ك", "ي", "ت", "ن", "أ", "س",
    )

    private val SUFFIXES = listOf(
        "كما", "هما", "كم", "هم", "هن", "نا", "ها", "ون", "ين", "ان", "ات", "وا",
        "تم", "تن", "ية", "ته", "تك",
        "ك", "ه", "ي", "ا", "ة", "ت", "ن",
    )

    private val VOWELS_AND_DIACRITICS = setOf(
        '\u064E', '\u064F', '\u0650', '\u0651', '\u0652',
        '\u064B', '\u064C', '\u064D', '\u0670',
        'ا', 'و', 'ى', 'ي', 'ء', 'ئ', 'ؤ', 'إ', 'أ', 'آ'
    )

    fun extractRoot(word: String): String {
        if (word.isEmpty() || word.length < 2) return word
        var w = word

        // Strip one prefix
        for (prefix in PREFIXES) {
            if (w.startsWith(prefix) && w.length - prefix.length >= 2) {
                w = w.substring(prefix.length)
                break
            }
        }

        // Strip one suffix
        for (suffix in SUFFIXES) {
            if (w.endsWith(suffix) && w.length - suffix.length >= 2) {
                w = w.substring(0, w.length - suffix.length)
                break
            }
        }

        // Extract consonants
        val consonants = w.filter { it !in VOWELS_AND_DIACRITICS }
        return when {
            consonants.length >= 3 -> "${consonants[0]}${consonants[1]}${consonants[2]}"
            consonants.length == 2 -> "${consonants[0]}${consonants[1]}"
            consonants.length == 1 -> "${consonants[0]}"
            else -> w
        }
    }
}
