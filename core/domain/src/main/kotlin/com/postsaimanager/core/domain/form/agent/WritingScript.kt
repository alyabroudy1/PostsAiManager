package com.postsaimanager.core.domain.form.agent

import java.util.Locale

/**
 * Which writing system a text is in, as plain Unicode data (no word lists, no language names): the agent's questions must be in the
 * script of the form's language, so a model that slips into another script is told to write in the form's language. It cannot tell
 * two languages of one script apart (English from German); that is the model's job, told in its instructions.
 */
object WritingScript {

    /** The script groups: Japanese and Chinese text mixes Han, Hiragana and Katakana, so they are one group. */
    private fun group(script: Character.UnicodeScript): Character.UnicodeScript = when (script) {
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Character.UnicodeScript.HAN
        else -> script
    }

    /** The non-Latin languages' scripts, by language code. Every other language is written in Latin (the registry grows by data alone). */
    private val SCRIPT_OF_LANGUAGE: Map<String, Character.UnicodeScript> = buildMap {
        listOf("ar", "fa", "ur", "ps", "ckb", "ug", "sd").forEach { put(it, Character.UnicodeScript.ARABIC) }
        listOf("he", "yi").forEach { put(it, Character.UnicodeScript.HEBREW) }
        listOf("ru", "uk", "bg", "be", "mk", "sr", "kk", "ky", "mn", "tg").forEach { put(it, Character.UnicodeScript.CYRILLIC) }
        listOf("hi", "mr", "ne", "sa").forEach { put(it, Character.UnicodeScript.DEVANAGARI) }
        listOf("zh", "ja").forEach { put(it, Character.UnicodeScript.HAN) }
        put("el", Character.UnicodeScript.GREEK)
        put("bn", Character.UnicodeScript.BENGALI)
        put("ta", Character.UnicodeScript.TAMIL)
        put("th", Character.UnicodeScript.THAI)
        put("ko", Character.UnicodeScript.HANGUL)
        put("ka", Character.UnicodeScript.GEORGIAN)
        put("hy", Character.UnicodeScript.ARMENIAN)
    }

    /** The script the language [locale] is written in. */
    fun of(locale: Locale): Character.UnicodeScript = SCRIPT_OF_LANGUAGE[locale.language.lowercase()] ?: Character.UnicodeScript.LATIN

    /** The script most of the letters of [text] are in; null when it has no letters. */
    fun dominant(text: String): Character.UnicodeScript? {
        val counts = HashMap<Character.UnicodeScript, Int>()
        text.codePoints().filter { Character.isLetter(it) }.forEach { cp ->
            val script = Character.UnicodeScript.of(cp)
            if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED && script != Character.UnicodeScript.UNKNOWN) {
                counts.merge(group(script), 1, Int::plus)
            }
        }
        return counts.maxByOrNull { it.value }?.key
    }
}

/**
 * A small data list of English function words, used only as a NEGATIVE signal: a question made mostly of them, with no word the form's
 * own text has, is plainly English on a form that is not (a model that slipped into English). It never decides what a text means, and a
 * question with any word of the form is never refused on its account. Words that are also common in German ("an", "in", "was", "will") are left out.
 */
object EnglishFunctionWords {
    /** The language code the list belongs to: a form in it is never refused for English words. */
    const val LANGUAGE = "en"

    val WORDS: Set<String> = setOf(
        "the", "is", "are", "were", "who", "what", "which", "when", "where", "why", "how", "for", "of", "to", "this", "that", "these",
        "those", "and", "or", "please", "tell", "me", "you", "your", "do", "does", "did", "have", "has", "can", "could", "would",
        "should", "it", "be", "with", "from", "my", "his", "her", "their", "there", "here", "not", "if", "about", "on", "at", "by", "any",
    )

    /** Whether at least half of [words] are English function words. */
    fun mostlyEnglish(words: List<String>): Boolean = words.isNotEmpty() && words.count { it in WORDS } * 2 >= words.size
}
