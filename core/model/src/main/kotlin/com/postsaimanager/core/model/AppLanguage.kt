package com.postsaimanager.core.model

/**
 * The language of the app's own screens, as the user picks it in Settings. [SYSTEM] follows the phone.
 *
 * [tag] is the BCP-47 tag handed to the platform's per-app language setting ("" means no override).
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    GERMAN("de"),
    ARABIC("ar"),
    ENGLISH("en"),
    ;

    companion object {
        /** The languages the app is translated into; a phone in any other language shows English. */
        val TRANSLATED: List<AppLanguage> = listOf(GERMAN, ARABIC, ENGLISH)

        /**
         * The choice stored by the platform for a language list such as "de" or "ar-EG,en" (only the first tag counts);
         * an empty list or a language the app is not translated into is [SYSTEM].
         */
        fun fromTags(tags: String): AppLanguage {
            val first = tags.split(',').firstOrNull()?.trim().orEmpty()
            val language = first.substringBefore('-').substringBefore('_').lowercase()
            return TRANSLATED.firstOrNull { it.tag == language } ?: SYSTEM
        }

        /**
         * The translated language the screens actually show: the pick, or for [SYSTEM] the phone's language when the app
         * is translated into it, English otherwise.
         */
        fun effective(selected: AppLanguage, systemLanguageTag: String): AppLanguage {
            if (selected != SYSTEM) return selected
            val language = systemLanguageTag.substringBefore('-').substringBefore('_').lowercase()
            return TRANSLATED.firstOrNull { it.tag == language } ?: ENGLISH
        }
    }
}
