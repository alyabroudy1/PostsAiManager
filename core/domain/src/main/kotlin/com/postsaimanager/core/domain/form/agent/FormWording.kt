package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.model.FormRole
import java.util.Locale

/**
 * The few words the agent's guidance needs in the form's language when the form itself gives none (a role's name, the "someone else"
 * choice). Implemented over the app's string resources (de/en/ar, any other language falls back to English); the agent never shows the
 * English enum name of a role to a model that then repeats it to the user.
 */
interface FormWording {
    /** What the person with [role] is called in [language] ("Kontoinhaber/in"). */
    fun roleName(role: FormRole, language: Locale): String

    /** The chip for "somebody who is not stored" in [language]. */
    fun someoneElse(language: Locale): String

    /** English words, for tests and as the fallback. */
    object English : FormWording {
        override fun roleName(role: FormRole, language: Locale): String = when (role) {
            FormRole.SUBJECT -> "person the form is for"
            FormRole.GUARDIAN -> "legal guardian"
            FormRole.PAYER -> "account holder"
            FormRole.SIGNER -> "signer"
            FormRole.EMERGENCY_CONTACT -> "emergency contact"
            FormRole.OTHER -> "other person"
        }

        override fun someoneElse(language: Locale): String = "Someone else"
    }
}
