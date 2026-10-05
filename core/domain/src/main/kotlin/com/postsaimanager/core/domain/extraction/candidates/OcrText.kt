package com.postsaimanager.core.domain.extraction.candidates

/**
 * The one way OCR text is normalised, shared by the candidate extractor and the quote verifier so
 * that a value found in the text and a quote of the same text always compare on the same characters.
 *
 * Only characters are unified (no-break spaces, Arabic-Indic digits and separators, carriage
 * returns); no words or spellings are touched.
 */
object OcrText {

    fun normalizeChars(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when (ch) {
                    ' ', ' ', ' ', ' ', ' ', ' ' -> ' '
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    '٫' -> ','
                    '٬' -> '.'
                    '\r' -> ' '
                    else -> ch
                },
            )
        }
        return sb.toString()
    }
}
