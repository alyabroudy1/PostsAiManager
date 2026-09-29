package com.postsaimanager.core.domain.extraction.candidates

/**
 * Keeps OCR noise from becoming candidates: TSE signatures, hash blobs, barcode digit runs. Applied to
 * whole lines before any candidate is looked for. Shape alone decides (long base64/hex/random tokens,
 * very long digit runs, symbol soup); label words never do.
 */
object NoiseFilter {

    private val IBAN_SHAPE = Regex("^[A-Z]{2}\\d{2}[A-Z0-9]{11,30}$")
    private val BLOB_CHARS = Regex("^[A-Za-z0-9+/=]+$")
    private val HEX = Regex("^[0-9a-fA-F]+$")

    fun isNoiseToken(token: String): Boolean {
        val t = token.trim(',', ';', ':', '(', ')', '"', '\'')
        if (t.length < 14) return false
        if (t.all { it.isDigit() }) return true // barcode / QR digit run
        if (t.length >= 32 && HEX.matches(t)) return true
        if (t.length < 20 || !BLOB_CHARS.matches(t)) return false
        if (IBAN_SHAPE.matches(t) && IbanValidator.lengths.containsKey(t.substring(0, 2))) return false
        // A long German word is letters only (capitalised or not); a signature or serial mixes
        // letters with digits or base64 symbols.
        val hasLetter = t.any { it.isLetter() }
        return hasLetter && (t.any { it.isDigit() } || t.any { it == '+' || it == '/' || it == '=' })
    }

    /**
     * A shape decides, never a word: a line is noise when one of its tokens has a machine shape
     * ([isNoiseToken]). No label word makes a line noise; receipt ids such as "Terminal-ID: 52847196"
     * stay ordinary candidates and the model decides whether they matter.
     */
    fun isNoiseLine(line: String): Boolean =
        line.split(Regex("\\s+")).any { it.isNotEmpty() && isNoiseToken(it) }
}
