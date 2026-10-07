package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.getOrNull
import com.postsaimanager.core.domain.contacts.LoadLetterContactsUseCase
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The system prompt a chat turn should be (re)primed with, plus whether it is the *whole*
 * story.
 *
 * @param text the grounding prompt itself — what [SendChatMessageUseCase] hands to
 *   [com.postsaimanager.core.domain.ai.AiEngine.ensureChatSession] as the cached, must-stay-
 *   stable KV prefix.
 * @param retrievalMode false means [text] already contains everything the model needs — the
 *   whole document fit, or [SendChatMessageUseCase] is not passing a callable document
 *   at all. True means [text] is necessarily incomplete (a summary and an excerpt, or —
 *   for a document-less chat — nothing document-specific at all) and
 *   [SendChatMessageUseCase] must run [RetrieveChunksUseCase] for *every* turn and inject
 *   the result into that turn's own prompt — never into [text], which has to stay identical
 *   across turns or the standing chat session's KV cache is invalidated and re-primed on
 *   every send. See [SendChatMessageUseCase]'s class KDoc, "Retrieval-augmented grounding".
 */
data class ChatGrounding(val text: String, val retrievalMode: Boolean)

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
 * ### Retrieval mode (4.1/4.2)
 *
 * When the whole document does not survive step 4 above, stuffing in a truncated fragment
 * of *whatever page happens to come first* is a poor substitute for the page that actually
 * answers the question. Instead this becomes a marker on the *conversation*, not the prompt:
 * [ChatGrounding.retrievalMode] is set, the grounding here shrinks to metadata + fields +
 * a first-page excerpt (still useful context, just not the whole letter), and
 * [SendChatMessageUseCase] takes over per turn — retrieving the passages most relevant to
 * *this question* and injecting them into *this turn's* prompt. That keeps the grounding
 * itself small and, critically, **stable**: the KV-cache prefix [SendChatMessageUseCase]
 * primes the standing chat session with must not change turn to turn, or every turn re-
 * prefills instead of reusing the cache (documentation/02-architecture.md §5.3). A
 * document-less chat ([invoke] called with `documentId = null`) is always in retrieval mode
 * for the same reason — there is no single document to fit in the first place, only the
 * whole corpus [RetrieveChunksUseCase] can search per question.
 *
 * Truncation (and retrieval mode generally) never happens silently: the excerpt says it is
 * partial, so a model that cannot find an answer in what it was given can say so instead of
 * confidently answering from a fragment.
 */
class BuildChatContextUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val profileRepository: ProfileRepository,
    private val letterContacts: LoadLetterContactsUseCase,
) {

    suspend operator fun invoke(
        documentId: String?,
        contextTokens: Int,
        reservedForReply: Int = DEFAULT_REPLY_RESERVE,
    ): ChatGrounding {
        if (documentId == null) return standaloneGrounding()

        val document = documentRepository.getDocumentById(documentId).getOrNull()
            ?.takeUnless { it.isTrashed }
            // Not the standalone flow (4.2) — a stale reference to a document that is gone
            // (or trashed: DocumentDetailScreen's chat entry point is hidden for a trashed
            // document, but a chat opened before that — e.g. re-opened from history — must
            // still degrade rather than keep grounding on deleted content). Nothing to
            // retrieve either: RetrieveChunksUseCase filtered to this documentId would only
            // ever come back empty, so there is no point in a per-turn retrieval step for it.
            ?: return ChatGrounding(STANDALONE_PROMPT, retrievalMode = false)

        val extracted = documentRepository.observeExtractedData(documentId).first()
        val profiles = profileRepository.getProfilesForDocument(documentId).first()
        val pages = documentRepository.getDocumentPages(documentId).getOrNull().orEmpty()

        // Budget in characters — token counts are only knowable inside the engine, and a
        // conservative chars-per-token ratio is safer here than an exact count obtained by
        // a round trip we would then have to invalidate.
        val budgetChars = ((contextTokens - reservedForReply - TEMPLATE_OVERHEAD_TOKENS)
            .coerceAtLeast(MIN_CONTEXT_TOKENS)) * CHARS_PER_TOKEN

        val header = buildHeader(document.title, document.documentType?.name, document.language)
        val read = LetterReadingContext.section(document.actionItems, extracted, letterContacts(documentId))
        // A value the "What was read" section already states is not listed again among the extracted details.
        val stated = LetterReadingContext.statedSlots(document.actionItems, extracted)
        val fields = buildFields(extracted.filter { it.slotKey == null || it.slotKey !in stated })
        val parties = buildProfiles(profiles)
        val instructions = INSTRUCTIONS

        val fixedCost = header.length + read.length + fields.length + parties.length + instructions.length
        val remainingForOcr = budgetChars - fixedCost

        val fullOcrText = pages.mapNotNull { it.ocrText }.joinToString("\n\n")
        val firstPageText = pages.firstOrNull()?.ocrText.orEmpty()

        // The whole document survives iff it fits, full stop — this is the *only* thing
        // that decides retrieval mode for a document chat. Everything below is just how the
        // grounding is worded once that is known.
        val retrievalMode = fullOcrText.isNotBlank() && fullOcrText.length > remainingForOcr

        val ocrSection = when {
            fullOcrText.isBlank() || remainingForOcr < MIN_OCR_CHARS -> ""
            !retrievalMode -> "\n## Document text\n$fullOcrText\n"
            // First page only, not a truncated cut of the *joined* text — a cut across the
            // combined text can land mid page-two, which is neither a whole excerpt nor
            // something a page citation could describe. The rest of the document is what
            // per-turn retrieval is for.
            firstPageText.length <= remainingForOcr ->
                "\n## Document text (first page only — ask a question to search the rest)\n" +
                    firstPageText + "\n"
            else ->
                "\n## Document text (first page, shortened to fit)\n" +
                    firstPageText.take(remainingForOcr - TRUNCATION_NOTE.length) +
                    TRUNCATION_NOTE
        }

        return ChatGrounding(header + read + fields + parties + ocrSection + instructions, retrievalMode)
    }

    /**
     * The title of [documentId], for labelling a passage retrieved in a document-less chat
     * (4.2) as "title, p.N". Reuses [documentRepository] rather than asking callers to
     * plumb their own document lookup for what is, here, a one-line convenience.
     */
    suspend fun documentTitle(documentId: String): String? =
        documentRepository.getDocumentById(documentId).getOrNull()?.title

    /**
     * Grounding for a document-less chat (4.2): the stable general prompt plus — cheap and
     * itself stable across turns — a short list of the user's document titles, so the model
     * at least knows what exists before any retrieval runs. Always retrieval mode: there is
     * no single document whose text could ever "fit", only a corpus
     * [RetrieveChunksUseCase] searches fresh per question.
     */
    private suspend fun standaloneGrounding(): ChatGrounding {
        // Only what the all-documents chat may see: health letters are not named here.
        val titles = documentRepository.getDocuments().first()
            .filter(ObserveChatVisibleDocumentsUseCase::isChatVisible)
            .take(MAX_STANDALONE_TITLES)
            .map { it.title }

        val text = if (titles.isEmpty()) {
            STANDALONE_PROMPT
        } else {
            buildString {
                append(STANDALONE_PROMPT)
                appendLine()
                appendLine()
                appendLine("## Your documents")
                titles.forEach { appendLine("- $it") }
            }
        }
        return ChatGrounding(text, retrievalMode = true)
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
         *
         * `internal`, not `private`: [SendChatMessageUseCase] reuses this exact heuristic to
         * budget chat history (task 3.3) and per-turn retrieved passages (task 4.1) — one
         * estimate, not several that could quietly drift apart on different sides of the
         * same context window.
         */
        internal const val CHARS_PER_TOKEN = 3

        internal const val DEFAULT_REPLY_RESERVE = 512
        internal const val TEMPLATE_OVERHEAD_TOKENS = 128
        internal const val MIN_CONTEXT_TOKENS = 256
        private const val MIN_OCR_CHARS = 200
        private const val LOW_CONFIDENCE = 0.6f

        /** Keeps the standalone grounding itself cheap and bounded on a large library. */
        private const val MAX_STANDALONE_TITLES = 20
    }
}
