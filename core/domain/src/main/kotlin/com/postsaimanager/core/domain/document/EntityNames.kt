package com.postsaimanager.core.domain.document

/**
 * Comparison key for a name or organisation string: trimmed and lower-cased so incidental
 * whitespace or casing differences are never treated as two different people.
 *
 * The single implementation of this rule; `EntityProfileLinker` in `:core:data` builds its dismissal keys with it.
 * It lived next to `EntityCoverageFilter` until that filter was removed with the "is this you?" suggestion cards;
 * the cleanup that deletes the linker's proposal half can move or delete it with them.
 */
fun normaliseEntityName(name: String): String = name.trim().lowercase()
