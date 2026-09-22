package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.getOrNull
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Assembles the system prompt that grounds a chat in a specific document.
 *
 * This is what separates "a chatbot inside a mail app" from "an assistant that knows your
 * mail". Without it the model answers from general knowledge and cannot see the letter in
 * front of the user.
 *
 * ### Why this lives in `:core:domain`
 *
 * Deciding *what the model is told* is domain logic, not an infrastructure detail — it
 * reads domain models through domain repositories and returns a string. The previous
 * `SystemPromptBuilder` sat in `:core:ai:core`, which no feature or use case may depend on
 * (architecture rule 1), which is part of why it never acquired a consumer.
 *
 * ### Context budgeting
 *
 * A local model has 2–8 k tokens of context; a multi-page German letter does not fit. The
 * old builder appended the full OCR text unconditionally, which would push the user's own
 * question out of the window — the model would receive the letter and no request.
 *
 * Content is therefore added in **descending order of signal per token**:
 *
 *  1. Instructions — small and non-negotiable.
 *  2. Document metadata — a handful of tokens.
 *  3. Extracted fields — already the *distilled* content of the letter. Sender, deadline,
 *     amount and reference cost a few dozen tokens and answer most questions outright.
 *  4. Raw OCR text — bulky and noisy, so it takes whatever budget survives.
 *
 * Truncation is announced in the prompt rather than silent, so the model can say the
 * document was shortened instead of confidently answering from a fragment.
 */
class BuildChatContextUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val profileRepository: ProfileRepository,
) {

    suspend operator fun invoke(
        documentId: String?,
        contextTokens: Int,
        reservedForReply: Int = DEFAULT_REPLY_RESERVE,
    ): String {
        if (documentId == null) return STANDALONE_PROMPT

        val document = documentRepository.getDocumentById(documentId).getOrNull()
            ?: return STANDALONE_PROMPT

        val extracted = documentRepository.observeExtractedData(documentId).first()
        val profiles = profileRepository.getProfilesForDocument(documentId).first()
        val pages = documentRepository.getDocumentPages(documentId).getOrNull().orEmpty()

        // Budget in characters — token counts are only knowable inside the engine, and a
        // conservative chars-per-token ratio is safer here than an exact count obtained by
        // a round trip we would then have to invalidate.
        val budgetChars = ((contextTokens - reservedForReply - TEMPLATE_OVERHEAD_TOKENS)
            .coerceAtLeast(MIN_CONTEXT_TOKENS)) * CHARS_PER_TOKEN

        val header = buildHeader(document.title, document.documentType?.name, document.language)
        val fields = buildFields(extracted)
        val parties = buildProfiles(profiles)
        val instructions = INSTRUCTIONS

        val fixedCost = header.length + fields.length + parties.length + instructions.length
        val remainingForOcr = budgetChars - fixedCost

        val ocrText = pages.mapNotNull { it.ocrText }.joinToString("\n\n")
        val ocrSection = when {
            ocrText.isBlank() || remainingForOcr < MIN_OCR_CHARS -> ""
            ocrText.length <= remainingForOcr ->
                "\n## Document text\n$ocrText\n"
            else ->
                // Keep the opening: in DIN 5008 correspondence the letterhead, reference
                // block and subject line come first and carry the most meaning.
                "\n## Document text (shortened to fit)\n" +
                    ocrText.take(remainingForOcr - TRUNCATION_NOTE.length) +
                    TRUNCATION_NOTE
        }

        return header + fields + parties + ocrSection + instructions
    }

    private fun buildHeader(title: String, type: String?, language: String?) = buildString {
        appendLine(ROLE)
        appendLine()
        appendLine("## Current document")
        appendLine("Title: $title")
        type?.let { appendLine("Type: $it") }
        language?.let { appendLine("Language: $it") }
    }

    private fun buildFields(extracted: List<ExtractedData>): String {
        if (extracted.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("## Extracted details")
            // Confirmed values first — the user has vouched for those, and a low-confidence
            // guess presented with equal weight invites the model to repeat it as fact.
            extracted.sortedByDescending { it.isConfirmed }.forEach { field ->
                append("- ${field.fieldName}: ${field.fieldValue}")
                if (!field.isConfirmed && field.confidence < LOW_CONFIDENCE) {
                    append(" (uncertain)")
                }
                appendLine()
            }
        }
    }

    private fun buildProfiles(profiles: List<Pair<Profile, ProfileRole>>): String {
        if (profiles.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("## Parties")
            profiles.forEach { (profile, role) ->
                append("- ${role.name.lowercase()}: ${profile.name}")
                profile.organization?.let { append(" ($it)") }
                appendLine()
            }
        }
    }

    companion object {
        private const val ROLE =
            "You are an assistant for managing postal correspondence. You help the " +
                "user understand, organise and reply to official letters."

        // The language rule is deliberately its own short, imperative line placed at the very
        // end of the prompt: small on-device models weight recent instructions more heavily,
        // and this one was previously getting lost (and contradicted by "unless" phrasing)
        // among the earlier, more general instructions above.
        private val INSTRUCTIONS = buildString {
            appendLine()
            appendLine("## Instructions")
            appendLine("- Answer using the document above. Do not invent details it does not contain.")
            appendLine("- If the answer is not in the document, say so plainly.")
            appendLine("- For a draft reply, use formal letter conventions.")
            appendLine("- Be concise.")
            appendLine(
                "- Always answer in the same language the user writes in. If the user writes " +
                    "in English, answer in English, even if the document is in another language.",
            )
        }

        private const val STANDALONE_PROMPT =
            "You are an assistant for managing postal correspondence. No document is " +
                "open, so answer generally and say when you would need the document to be " +
                "more specific. Always answer in the same language the user writes in."

        private const val TRUNCATION_NOTE =
            "\n[… the rest of this document was omitted to fit the context window …]\n"

        /**
         * Conservative chars-per-token. German compounds tokenise worse than English, so
         * under-estimating here costs a little unused context; over-estimating truncates
         * the user's question instead.
         */
        private const val CHARS_PER_TOKEN = 3

        private const val DEFAULT_REPLY_RESERVE = 512
        private const val TEMPLATE_OVERHEAD_TOKENS = 128
        private const val MIN_CONTEXT_TOKENS = 256
        private const val MIN_OCR_CHARS = 200
        private const val LOW_CONFIDENCE = 0.6f
    }
}
