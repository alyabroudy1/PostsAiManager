package com.postsaimanager.core.domain.timeline

import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.model.ExtractedData
import java.util.Locale

/**
 * The reference values of a document as exact-match keys: what ties letters of one organisation into one matter (a file number, a
 * customer, contract or policy number, a reference the letter cites).
 *
 * Which stored values are references is the schema's (a slot of kind reference or reference list). A value becomes a key by
 * normalising only its shape, never its meaning: letters and digits kept, upper-cased, the rest (spaces, dashes, slashes) dropped, any
 * script's digits read as digits. A key must be [MIN_LENGTH] characters long and hold a digit, so a stray word is never a reference. Two
 * letters share a reference when they share a key exactly; nothing is fuzzy.
 */
object ReferenceKeys {

    const val MIN_LENGTH = 4

    private val SEPARATORS = Regex("[,;\\n]")

    /** The keys of a document's live reference values. */
    fun of(fields: List<ExtractedData>, schema: ExtractionSchema = ExtractionSchema.DEFAULT): Set<String> {
        val referenceSlots = schema.allSlots.filter { it.kind == SlotKind.REFERENCE || it.kind == SlotKind.REFERENCE_LIST }.map { it.json }.toSet()
        return fields.asSequence()
            .filter { !it.deletedByUser && it.slotKey in referenceSlots }
            .flatMap { it.fieldValue.split(SEPARATORS).asSequence() }
            .mapNotNull(::normalise)
            .toSet()
    }

    /** [value] as a key, or null when it is too short or holds no digit to be a reference. */
    fun normalise(value: String): String? {
        val key = buildString {
            for (c in value) {
                when {
                    Character.isDigit(c) -> append(Character.digit(c, 10))
                    Character.isLetter(c) -> append(c.toString().uppercase(Locale.ROOT))
                }
            }
        }
        return key.takeIf { it.length >= MIN_LENGTH && it.any(Char::isDigit) }
    }
}
