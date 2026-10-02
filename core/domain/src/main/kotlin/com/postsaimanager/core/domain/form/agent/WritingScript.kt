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
