package com.postsaimanager.feature.documents

import com.postsaimanager.core.domain.form.agent.FormRefs

/**
 * The one owner of "is this name the person themself": the Pages tab's card and the Extracted tab's "For" line both ask here, so they
 * cannot disagree. Code only verifies: the folded names (case, accents and spacing ignored) must be equal, nothing fuzzier.
 */
internal object PartyRecipients {

    /** [PagesRecipient.You] when [name] folds to [selfName] (the "Me" profile's name), else the name as read. */
    fun of(name: String, selfName: String?): PagesRecipient {
        val me = selfName?.let(FormRefs::fold)?.takeIf { it.isNotEmpty() }
        return if (me != null && FormRefs.fold(name.trim()) == me) PagesRecipient.You else PagesRecipient.Named(name.trim())
    }

    /** Whether two printed names are the same person's by the same folded comparison. */
    fun sameName(a: String, b: String): Boolean {
        val folded = FormRefs.fold(a.trim())
        return folded.isNotEmpty() && folded == FormRefs.fold(b.trim())
    }
}
