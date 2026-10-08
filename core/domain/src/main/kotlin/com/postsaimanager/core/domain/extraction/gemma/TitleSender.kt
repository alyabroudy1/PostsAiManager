package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier

/**
 * The one owner of "which party may stand in a document's title": the SENDER, or none; never the addressee.
 *
 * The title's party is the sender the reading stored. When that name is printed in the letter's address field (the window-envelope
 * block, by position on the page: the addressee's place) it is the addressee, whatever role the model gave it: it is then left out of
 * the title instead of putting the reader's own name in front of the letter's subject. Geometry decides, not the words.
 */
object TitleSender {

    /** [senderName] as the title's party, or null when there is none or it is the name printed in the address field. */
    fun of(senderName: String?, layout: LetterLayout): String? {
        val sender = senderName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val folded = QuoteVerifier.fold(sender)
        val addressee = layout.pages.flatMap { it.zone(LetterZone.ADDRESS_FIELD) }.map { QuoteVerifier.fold(it.text) }
        return sender.takeUnless { addressee.any { line -> line == folded || line.contains(folded) } }
    }
}
