package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.OcrBlock
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Reads a document with the local model and returns what it understood.
 *
 * ### Why this replaces pattern matching
 *
 * The regex extractor encodes one layout in one language. On a clean German letter it read
 * the salutation "Frau" as the recipient's name and a fragment of the body as the sender's
 * organisation — both reported at a hardcoded 0.80, because its confidences are constants
 * per field kind rather than measurements of anything. Every new sender format, and every
 * language, is another set of patterns to write and maintain.
 *
 * ### Why the output is grammar-constrained
 *
 * The sampler is given a GBNF grammar, so the model **physically cannot** emit text that
 * violates the schema — no missing brace, no prose apology before the JSON, no invented
 * enum value. That removes the entire class of "parse the model's answer" bugs, which are
 * the usual reason structured extraction is unreliable. Verified working on device: a 0.5B
 * model produced valid constrained JSON.
 *
 * ### Why it is given layout, not text
 *
 * [DocumentLayout.describe] labels each block with where it sits — address block, reference
 * block, footer. A model asked to find "the recipient" in a flat string has only wording to
 * go on; given position it can prefer a name in the address block over the same name in a
 * signature. This is what makes one prompt work across sender formats rather than one
 * template.
 */
class AiExtractionUseCase @Inject constructor(
    private val engine: AiEngine,
    private val activeModelProvider: ActiveModelProvider,
) {

    /**
     * @param contextTokens overrides the window to budget against. Normally left null so it
     *   comes from the same source the model is loaded with — a budget computed against a
     *   different number than the one allocated is how a prompt silently overflows.
     */
    suspend operator fun invoke(
        blocks: List<OcrBlock>,
        contextTokens: Int? = null,
    ): PamResult<DocumentUnderstanding> {
        if (blocks.isEmpty()) return PamResult.Success(DocumentUnderstanding())

        val window = contextTokens ?: activeModelProvider.activeModelContextTokens()

        // Loaded on demand, like the chat path. Processing usually runs in the background
        // straight after a scan, when nothing has had reason to load a model yet — failing
        // here because the user has not opened chat would be arbitrary.
        if (!engine.isReady) {
            val path = activeModelProvider.activeModelPath()
                ?: return PamResult.Error(PamError.ModelNotLoaded("document understanding"))
            val loaded = engine.load(path, window)
            if (loaded is PamResult.Error) return loaded
        }

        val page = DocumentLayout.describe(blocks).let { described ->
            val budget = characterBudget(window)
            // Truncating the tail keeps the header, address and reference blocks — where
            // almost everything structured lives. The body is what a long letter has too
            // much of, and it contributes least to the fields being extracted.
            if (described.length <= budget) described else described.take(budget)
        }

        val prompt = engine.formatPrompt(
            listOf(
                AiChatMessage(AiChatRole.SYSTEM, SYSTEM_PROMPT),
                AiChatMessage(AiChatRole.USER, page),
            ),
        )

        val raw = try {
            engine.generate(
                AiRequest(
                    prompt = prompt,
                    maxTokens = MAX_TOKENS,
                    // Near-deterministic: this is reading, not writing. The same document
                    // should not yield different fields on two runs, or the merge would
                    // report a disagreement that is only sampling noise.
                    temperature = 0.1f,
                    grammar = GRAMMAR,
                ),
            ).toList().joinToString("")
        } catch (e: Exception) {
            return PamResult.Error(PamError.InferenceError("Reading the document failed", e))
        }

        return parse(raw)
    }

    /**
     * The grammar makes malformed JSON impossible, but not impossible to *truncate*: a model
     * that hits [MAX_TOKENS] mid-object leaves valid-so-far text that is not valid JSON. So
     * this still has to fail gracefully rather than assume.
     */
    private fun parse(raw: String): PamResult<DocumentUnderstanding> {
        val text = raw.trim()
        if (text.isEmpty()) {
            return PamResult.Error(PamError.InferenceError("The model returned nothing"))
        }

        return try {
            val parsed = json.decodeFromString(DocumentUnderstanding.serializer(), text)
            PamResult.Success(sanitise(parsed))
        } catch (e: Exception) {
            // Carries a slice of the answer, not just the parser's complaint. Whether the
            // model produced sense that was cut off or nonsense that parsed is the whole
            // diagnosis, and without it the failure is invisible at the call site.
            //
            // In the message rather than a log line: this is `:core:domain`, and reaching
            // for android.util.Log here would make a pure layer untestable off-device —
            // Log throws "not mocked" under JVM unit tests, which is how this arrived.
            PamResult.Error(
                PamError.InferenceError(
                    "Could not read the model's answer: ${e.message}. " +
                        "Answer began: ${text.take(200)}",
                    e,
                ),
            )
        }
    }

    /**
     * Drops what cannot be used and clamps what can.
     *
     * A grammar constrains *shape*, not *sense*: it permits an entity with an empty name and
     * a confidence of 0.99. Storing those would create nameless profiles and make the review
     * queue meaningless.
     */
    private fun sanitise(understanding: DocumentUnderstanding) = understanding.copy(
        entities = understanding.entities
            .filter { it.name.isNotBlank() }
            .map { it.copy(name = it.name.trim(), confidence = it.confidence.coerceIn(0f, 1f)) }
            // The same organisation named twice in one letter is one entity.
            .distinctBy { it.name.lowercase() to it.role },
        facts = understanding.facts
            .filter { it.value.isNotBlank() }
            .map { it.copy(value = it.value.trim(), confidence = it.confidence.coerceIn(0f, 1f)) },
    )

    /**
     * Room for the page, leaving the reply and the system prompt their share.
     *
     * Three characters per token is the same estimate `BuildChatContextUseCase` uses; German
     * compounds make it conservative, which is the right direction to be wrong in.
     */
    private fun characterBudget(contextTokens: Int): Int =
        ((contextTokens - MAX_TOKENS - SYSTEM_PROMPT_TOKENS).coerceAtLeast(512)) * CHARS_PER_TOKEN

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val CHARS_PER_TOKEN = 3
        private const val MAX_TOKENS = 768
        private const val SYSTEM_PROMPT_TOKENS = 320

        internal val SYSTEM_PROMPT = """
            You read scanned business letters and return structured data as JSON.

            Each input line is one block of text from the page, prefixed with where it sits:
            [zone @ x%,y%]. Use the position. In a German business letter the sender is in
            the header, the recipient is in the address block on the left, and the reference
            block on the right holds Aktenzeichen, Ihr Zeichen and the date.

            Identify:
            - entities: every person and organisation. kind is what they are (PERSON,
              AUTHORITY, COMPANY, OTHER). role is what they do in THIS letter: SENDER,
              RECIPIENT, SENDER_CONTACT for a person acting for the sender, MENTIONED for
              anyone named in the body. Use relation for how a mentioned person connects to
              the recipient, for example "spouse of the recipient". Leave it empty otherwise.
            - facts: reference numbers, dates, amounts, IBANs. Use DEADLINE only for a date
              the recipient must act by, and DATE for the letter's own date.

            List only what matters: the sender, the recipient, any named contact, and people
            actually named in the body. At most 8 entities and 10 facts — choose the
            important ones rather than every capitalised phrase. Do not repeat an entity.

            Set confidence to how sure you are, between 0 and 1. Be honest: use a low value
            when the text is unclear or you are inferring. Do not invent anything that is not
            on the page. Return only what you actually find.
        """.trimIndent()

        /**
         * GBNF constraining the reply to exactly the shape of `DocumentUnderstanding`.
         *
         * Enum members are literal alternatives, so the model cannot coin a role that does
         * not exist and force the parser to guess. Confidence is restricted to one or two
         * decimals, which is all the precision the value carries.
         *
         * The list lengths are **bounded**, and that is not tidiness. Left unbounded, a 2B
         * model reading a one-page letter emitted fourteen entities and was still going
         * when it hit the token limit — leaving JSON that was valid right up to the point
         * it stopped, and therefore unparseable. A grammar that cannot run away is cheaper
         * than a larger token budget and produces a better answer, since the model has to
         * choose what matters instead of listing every capitalised phrase.
         */
        val GRAMMAR = """
            root ::= "{" ws "\"language\":" ws string "," ws "\"documentType\":" ws string "," ws "\"subject\":" ws string "," ws "\"entities\":" ws entities "," ws "\"facts\":" ws facts ws "}"

            entities ::= "[" ws (entity (ws "," ws entity){0,7})? ws "]"
            entity ::= "{" ws "\"name\":" ws string "," ws "\"kind\":" ws kind "," ws "\"role\":" ws role "," ws "\"relation\":" ws string "," ws "\"confidence\":" ws conf ws "}"

            facts ::= "[" ws (fact (ws "," ws fact){0,9})? ws "]"
            fact ::= "{" ws "\"label\":" ws string "," ws "\"value\":" ws string "," ws "\"kind\":" ws factkind "," ws "\"confidence\":" ws conf ws "}"

            kind ::= "\"PERSON\"" | "\"AUTHORITY\"" | "\"COMPANY\"" | "\"OTHER\""
            role ::= "\"SENDER\"" | "\"RECIPIENT\"" | "\"SENDER_CONTACT\"" | "\"MENTIONED\""
            factkind ::= "\"REFERENCE\"" | "\"DATE\"" | "\"DEADLINE\"" | "\"AMOUNT\"" | "\"IBAN\"" | "\"SUBJECT\"" | "\"OTHER\""

            conf ::= "0." [0-9] [0-9]? | "1" | "0"
            string ::= "\"" char* "\""
            char ::= [^"\\] | "\\" ["\\bfnrt/]
            ws ::= [ \t\n]*
        """.trimIndent()
    }
}
