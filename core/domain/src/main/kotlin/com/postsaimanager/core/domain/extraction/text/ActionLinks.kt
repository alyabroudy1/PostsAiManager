package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.domain.extraction.address.AddressRows
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.ExtractedData

/**
 * Finds the stored fields behind an action line, so a person can confirm or correct the values a line states. Code only verifies: the
 * line was written by the model and checked by [ActionWriter]; this reads which stored values it quotes.
 *
 * A field is behind a line when every word and number of its value occurs in the line (folded, so digits and separators compare the
 * same way the writer's check did). The parties, the subject and the address rows are never linked: they have their own place on the
 * tab. A line is **stale** when a person changed a linked value since the line was written (the line still quotes the machine's earlier
 * value, not the current one); a stale line is not shown, because it would state something the person has corrected.
 *
 * Pure.
 */
object ActionLinks {

    /** An action line with the live fields it quotes (possibly none: a line may state no value at all). */
    class Linked(val text: String, val rows: List<ExtractedData>)

    /** The lines of [lines] that are not stale, each with its fields from [fields] (the live rows only are considered). */
    fun link(lines: List<String>, fields: List<ExtractedData>): List<Linked> {
        val candidates = fields.filter { it.isLive && linkable(it) }
        return lines.mapNotNull { line ->
            val words = tokens(line).toSet()
            val behind = candidates.filter { quotedIn(it.fieldValue, words) }
            val staleRow = candidates.any { row ->
                !quotedIn(row.fieldValue, words) && row.machineValue?.let { quotedIn(it, words) } == true
            }
            if (staleRow) null else Linked(line, behind)
        }
    }

    /** Whether a stored field is one an action line may be about (not a party, the subject or an address part). */
    private fun linkable(row: ExtractedData): Boolean {
        val key = row.slotKey
        if (AddressRows.isAddressKey(key)) return false
        return key !in NOT_LINKED
    }

    private fun quotedIn(value: String, lineWords: Set<String>): Boolean {
        val words = tokens(value)
        return words.isNotEmpty() && words.sumOf { it.length } >= MIN_LETTERS && words.all { it in lineWords }
    }

    private fun tokens(s: String): List<String> = TOKEN.findAll(QuoteVerifier.fold(s)).map { it.value }.toList()

    private val ExtractedData.isLive: Boolean get() = !deletedByUser && reviewState != com.postsaimanager.core.model.ReviewState.IGNORED

    private val NOT_LINKED = setOf(
        UnderstandingToFields.SLOT_SENDER, UnderstandingToFields.SLOT_ADDRESSEE, UnderstandingToFields.SLOT_CONTACT,
        UnderstandingToFields.SLOT_SUBJECT, UnderstandingToFields.SLOT_SUBJECT_PERSON,
    )

    /** A value shorter than this (a lone digit, a short word) says nothing about which field a line quotes. */
    private const val MIN_LETTERS = 3

    private val TOKEN = Regex("[\\p{L}\\p{Nd}]+")
}
