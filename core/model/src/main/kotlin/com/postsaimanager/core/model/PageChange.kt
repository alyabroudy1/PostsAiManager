package com.postsaimanager.core.model

/**
 * A change to the pages of a document after it was created, as the one rule that says where each old page number goes. Everything that refers
 * to a page by its number (a value's evidence and its box, a chat citation, a form field's page) is moved by [newNumber]; a reference to a page
 * that is gone has no new number.
 */
sealed interface PageChange {

    /** The number [old] becomes, or null when that page no longer exists. */
    fun newNumber(old: Int): Int?

    /** Page [pageNumber] is removed; every later page moves one place up. */
    data class Delete(val pageNumber: Int) : PageChange {
        override fun newNumber(old: Int): Int? = when {
            old == pageNumber -> null
            old > pageNumber -> old - 1
            else -> old
        }
    }

    /** The pages are put in a new order: [order] lists the old page numbers, in the order the pages now have (the first becomes page 1). */
    data class Reorder(val order: List<Int>) : PageChange {
        override fun newNumber(old: Int): Int? = order.indexOf(old).takeIf { it >= 0 }?.plus(1)
    }
}
