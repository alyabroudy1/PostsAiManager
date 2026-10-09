package com.postsaimanager.core.domain.settings

import com.postsaimanager.core.model.AppLanguage

/**
 * The language of the app's own screens: what the user picked in Settings, and the way to change it. The platform keeps the choice
 * (per-app language setting), so there is exactly one owner of it. Changing it recreates the visible screens in the new language.
 */
interface AppLanguageSettings {

    /** The user's pick; [AppLanguage.SYSTEM] while the app follows the phone. */
    fun current(): AppLanguage

    /** Applies and stores the pick. Call it on the main thread. */
    fun select(language: AppLanguage)
}

/**
 * The language the AI writes its own texts in (the summary, the key-fact labels, the session notes): the app language, which for a
 * "System" pick is the phone's language when the app is translated into it, English otherwise. Only an input to the prompts; the
 * model still reads letters in any language, and the chat answers in the language the user writes in.
 */
interface AppLanguageProvider {

    /** The BCP-47 language code: "de", "ar" or "en". */
    fun aiLanguageCode(): String
}
