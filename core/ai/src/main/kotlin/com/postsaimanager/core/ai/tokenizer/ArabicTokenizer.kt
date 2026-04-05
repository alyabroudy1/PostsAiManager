package com.postsaimanager.core.ai.tokenizer

import kotlin.math.absoluteValue

/**
 * A utility to bridge raw Arabic strings into the tens of dense Long arrays
 * required by the HRM-Grid V2 ONNX parser.
 * This is a deterministic string-hash and char-mapping implementation that mocks
 * the deeper CAMeL Tools pipeline temporarily for local Android ONNX verification.
 */
class ArabicTokenizer {

    companion object {
        const val MAX_WORDS = 32
        const val MAX_CHARS = 16
        const val MAX_SUBWORDS = 4

        // Modulo constraints matching `ParserConfig` and Python dummy tests
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
        // Clean and simple split by whitespace
        val rawWords = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val words = mutableListOf("<ROOT>") // 0 index is the ROOT dependency node
        words.addAll(rawWords)

        // Clip to MAX_WORDS - 1 (because index 0 is root)
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
            if (w == 0) continue // <ROOT> stays mostly 0 internally

            val wordStr = validWords[w]
            
            // Generate a deterministic integer ID using hash
            val baseHash = wordStr.hashCode().absoluteValue
            
            val wId = (baseHash % WORD_VOCAB).toLong().coerceAtLeast(1)
            wordIds[w] = wId
            
            // Pseudo-random but deterministic feature assignments matching the python dummy
            posTags[w] = (wId % POS_VOCAB).coerceAtLeast(1)
            rootIds[w] = wId % ROOT_VOCAB
            patternIds[w] = wId % PATTERN_VOCAB
            procliticIds[w] = wId % PROCLITIC_VOCAB
            encliticIds[w] = wId % ENCLITIC_VOCAB

            // Determine Chars (up to MAX_CHARS)
            val charLen = minOf(wordStr.length, MAX_CHARS)
            for (c in 0 until charLen) {
                val flatIndex = w * MAX_CHARS + c
                val cId = wordStr[c].code.toLong()
                
                charIds[flatIndex] = (cId % CHAR_VOCAB).coerceAtLeast(1)
                diacIds[flatIndex] = cId % DIAC_VOCAB
            }

            // Pseudo BPEs (just repeat word hash chunks)
            for (s in 0 until MAX_SUBWORDS) {
                val flatIndex = w * MAX_SUBWORDS + s
                bpeIds[flatIndex] = ((baseHash / (s + 1)) % BPE_VOCAB).toLong().coerceAtLeast(1)
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
}
