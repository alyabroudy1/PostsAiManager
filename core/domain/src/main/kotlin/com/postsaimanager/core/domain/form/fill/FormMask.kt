package com.postsaimanager.core.domain.form.fill

/** How a sensitive value is shown before the user reveals it: bullets and its last four characters. */
object FormMask {
    fun of(value: String): String = "••••" + value.filter(Char::isLetterOrDigit).takeLast(4)
}
