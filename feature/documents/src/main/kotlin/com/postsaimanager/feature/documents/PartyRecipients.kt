package com.postsaimanager.feature.documents

import com.postsaimanager.core.domain.document.list.PartyNames

/**
 * "Is this name the person themself" for the Pages tab's card and the Extracted tab's "For" line. The matching itself has one owner,
 * [PartyNames] in `:core:domain`, which the document list's person chips use too, so none of them can disagree.
 */
internal object PartyRecipients {

    /** [PagesRecipient.You] when [name] names the "Me" profile ([selfName]), else the name as read. */
    fun of(name: String, selfName: String?): PagesRecipient =
        if (selfName != null && PartyNames.names(name, selfName)) PagesRecipient.You else PagesRecipient.Named(name.trim())

    /** Whether two printed names are the same person's by the same folded comparison. */
    fun sameName(a: String, b: String): Boolean = PartyNames.sameName(a, b)
}
