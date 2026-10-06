package com.postsaimanager.core.domain.document.list

import javax.inject.Inject

/** Which side of a letter a name on a list row belongs to. */
enum class DocumentParty { SENDER, ADDRESSEE }

/**
 * The seam between a name as the letter printed it and the name a list row shows. Today the printed
 * name is shown as is ([IdentityPartyNameResolver]); when profiles can say "this addressee is Mia", a
 * resolver backed by them replaces the binding and no screen changes.
 */
fun interface PartyNameResolver {
    fun resolve(party: DocumentParty, printed: String): String
}

class IdentityPartyNameResolver @Inject constructor() : PartyNameResolver {
    override fun resolve(party: DocumentParty, printed: String): String = printed
}
