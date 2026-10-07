package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.v2.RawExtra
import com.postsaimanager.core.domain.extraction.v2.StructuredGrammar

/**
 * Writes the open "key information": the facts the model finds a person would need from THIS document beyond the fields already read.
 *
 * One generation, bounded by [KeyInfoFormat.grammar] and [KeyInfoFormat.MAX_TOKENS], on the session the reading already opened (the letter is
 * the prefix, so nothing is read twice). The prompt shows the read fields so the model does not repeat them; [KeyInfoVerifier] then keeps only
 * the facts whose value is in the letter and is not a read field. The model decides what matters and how to call it; code only verifies.
 *
 * A failed or empty answer means no key information: the reading never fails over it.
 */
class KeyInfoWriter(
    private val session: PromptSession,
    private val verifier: KeyInfoVerifier = KeyInfoVerifier(),
) {

    /**
     * @param read the fields already read, as (label, value), shown to the model and used to drop duplicates
     * @param ocrText the letter's text, the grounding reference
     * @param languageCode the document's language as a BCP-47 code, or null when unknown
     * @return the facts as open extras: a quoted value (no candidate), the label as the model wrote it
     */
    suspend fun write(read: List<Pair<String, String>>, ocrText: String, languageCode: String?): List<RawExtra> {
        val answer = (session.ask(prompt(read, languageCode), KeyInfoFormat.grammar(), KeyInfoFormat.MAX_TOKENS) as? PamResult.Success)?.data
            ?: return emptyList()
        return verifier.verify(KeyInfoFormat.parse(answer), ocrText, read.map { it.second }).map {
            RawExtra(label = it.label, key = KEY, id = StructuredGrammar.NONE, value = it.value, confidence = CONFIDENCE)
        }
    }

    internal fun prompt(read: List<Pair<String, String>>, languageCode: String?): String = buildString {
        append("READ FIELDS (already known; do not repeat them):\n")
        if (read.isEmpty()) append("- none\n")
        read.forEach { (label, value) -> append("- ").append(label).append(": ").append(value).append('\n') }
        append("\nQUESTION: List up to ").append(KeyInfoFormat.MAX_FACTS).append(" other facts a person would need from THIS document ")
        append("that are not among the read fields. Each fact is one line: a short label, a colon and the value copied exactly as it is printed. ")
        append(languageCode?.trim()?.takeIf { it.isNotEmpty() }?.let { "Write the labels in the language with the code \"$it\". " } ?: "Write the labels in the document's own language. ")
        append("Answer ").append(KeyInfoFormat.NONE).append(" when there is nothing more.")
        append("\nANSWER FORMAT: label: value, one fact per line")
    }

    companion object {
        /** The key of an open extra: metadata for grouping only, never the identity (the label is). */
        const val KEY = "key_info"

        /** The model's own word for its confidence: the grammar asks none, and a verified quote is capped by the verifier anyway. */
        const val CONFIDENCE = "MEDIUM"
    }
}
