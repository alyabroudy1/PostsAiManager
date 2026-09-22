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

        // The caller's override (if any) replaces only the context window on top of the
        // device-sized defaults — threads, mmap/mlock and the rest still come from the
        // single source of truth in InferenceConfig.defaults.
        val baseConfig = activeModelProvider.extractionModelConfig()
        val config = contextTokens?.let { baseConfig.copy(contextTokens = it) } ?: baseConfig
        val window = config.contextTokens

        // Loaded on demand, like the chat path, and — like the chat path — reconciled on
        // every call rather than only when `!engine.isReady`: the engine may be Ready on
        // the *chat* model, or on the extraction model with a stale accelerator/thread
        // config the user changed since. `engine.load` is cheap when nothing actually
        // changed (`ModelLoadCoordinator` dispatches a same-model, same-config request to
        // `ReloadScope.NONE`), and this is what keeps `ModelLoadCoordinator.lastRequested`
        // current for crash recovery — see `SendChatMessageUseCase` for the same fix and
        // the bug it replaces.
        //
        // When the reading model differs from the chat model this reload is not free: the
        // engine holds one model at a time, so alternating between reading a document and
        // answering a question pays a load each way. Acceptable because reading is
        // background work and the default is a single shared model; if it becomes a problem
        // the answer is scheduling, not two engines in memory at once.
        val path = activeModelProvider.extractionModelPath()
            ?: return PamResult.Error(PamError.ModelNotLoaded("document understanding"))
        val loaded = engine.load(path, config)
        if (loaded is PamResult.Error) return loaded

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

        // Plain text, not the zone-labelled `page` sent to the model: labels like
        // "[address block @ 8%,18%]" would themselves become substrings a value could
        // spuriously match against, and "%" or a stray "8" is exactly the kind of short
        // token that could launder a bad reading. Grounding checks the document, not the
        // prompt.
        val groundingText = DocumentLayout.plainText(blocks)

        return parse(raw, groundingText)
    }

    /**
     * The grammar makes malformed JSON impossible, but not impossible to *truncate*: a model
     * that hits [MAX_TOKENS] mid-object leaves valid-so-far text that is not valid JSON. So
     * this still has to fail gracefully rather than assume.
     *
     * A straight parse failure is not the end: [salvage] tries once to recover whatever
     * entities and facts were already complete before the cut. Only if that finds nothing
     * usable does this report the original error.
     */
    private fun parse(raw: String, pageText: String): PamResult<DocumentUnderstanding> {
        val text = raw.trim()
        if (text.isEmpty()) {
            return PamResult.Error(PamError.InferenceError("The model returned nothing"))
        }

        return try {
            val parsed = json.decodeFromString(DocumentUnderstanding.serializer(), text)
            PamResult.Success(sanitise(parsed, pageText))
        } catch (e: Exception) {
            salvage(text)?.let { recovered ->
                return PamResult.Success(sanitise(recovered, pageText).copy(truncated = true))
            }
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
     * Recovers the entities and facts the model had already finished before generation was
     * cut off, discarding whatever came after the last complete one.
     *
     * ### What this is
     *
     * [closeAtLastCompleteElement] finds the last point in the text where a whole entity or
     * fact object had just been closed while its array was still open, cuts there, and
     * closes whatever braces/brackets were still open at that point. That is valid JSON
     * built only from text the model actually finished writing.
     *
     * ### What this deliberately does not do
     *
     * This is not a JSON repair library. It does not fix a broken value, complete a
     * half-written string, guess a missing field, or salvage anything from inside a
     * partially-written element — only whole elements survive. If `language`,
     * `documentType` or `subject` themselves were cut short (they come first in the
     * grammar, so this only happens on a very early truncation), or if not even one entity
     * or fact was completed, [closeAtLastCompleteElement] finds no safe cut point and this
     * returns null — callers fall back to reporting the original parse error.
     */
    private fun salvage(text: String): DocumentUnderstanding? {
        val closed = closeAtLastCompleteElement(text) ?: return null
        return try {
            json.decodeFromString(DocumentUnderstanding.serializer(), closed)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Scans [text] tracking brace/bracket nesting and string/escape state, and remembers the
     * index right after every `}` that closes one whole array element (an entity or a fact)
     * while that array is still open. The last such index is the safest place to cut: it is
     * the most that could have been written and still be a set of complete elements.
     *
     * Returns the text up to that cut, followed by the closing braces/brackets needed to
     * balance whatever containers were still open at that point — or null if no element was
     * ever completed, or the brackets are malformed and cutting anywhere would be a guess.
     */
    private fun closeAtLastCompleteElement(text: String): String? {
        val stack = mutableListOf<Char>()
        var inString = false
        var escaped = false
        var lastCutIndex: Int? = null
        var lastCutStack: List<Char>? = null

        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.add(c)
                '}' -> {
                    if (stack.removeLastOrNull() != '{') return null
                    if (stack.lastOrNull() == '[') {
                        lastCutIndex = i + 1
                        lastCutStack = stack.toList()
                    }
                }
                ']' -> {
                    if (stack.removeLastOrNull() != '[') return null
                }
            }
        }

        val cut = lastCutIndex ?: return null
        val remainingOpen = lastCutStack ?: return null
        val closingSuffix = remainingOpen.asReversed()
            .joinToString("") { if (it == '{') "}" else "]" }
        return text.substring(0, cut) + closingSuffix
    }

    /**
     * Drops what cannot be used, and replaces confidence rather than trusting or clamping it.
     *
     * A grammar constrains *shape*, not *sense*: it permits an entity with an empty name and
     * a confidence of 0.99. Storing those would create nameless profiles and make the review
     * queue meaningless.
     *
     * The model's own `confidence` is discarded outright, not clamped and not blended with
     * the derived score: on every model tried on device it is 0.9 for almost every field, a
     * constant rather than a measurement, and averaging a constant into a real signal only
     * compresses the real signal. See [ExtractionConfidence] for what replaces it.
     */
    private fun sanitise(understanding: DocumentUnderstanding, pageText: String) = understanding.copy(
        entities = understanding.entities
            .filter { it.name.isNotBlank() }
            .map {
                it.copy(
                    name = it.name.trim(),
                    confidence = ExtractionConfidence.forEntity(it, pageText),
                )
            }
            // The same organisation named twice in one letter is one entity.
            .distinctBy { it.name.lowercase() to it.role },
        facts = understanding.facts
            .filter { it.value.isNotBlank() }
            .map {
                it.copy(
                    value = it.value.trim(),
                    confidence = ExtractionConfidence.forFact(it, pageText),
                )
            },
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

        /**
         * Conservative chars-per-token, same estimate and same direction of error as
         * `BuildChatContextUseCase` uses for the input side: German compounds tokenise
         * worse than this assumes, so it under-counts how many tokens a given amount of
         * text needs — safe for a *budget* in both directions, since that means we never
         * assume we have more room (input) or need fewer tokens (output) than we really do.
         */
        internal const val CHARS_PER_TOKEN = 3

        // ── How many entities/facts the grammar permits ──
        //
        // These two numbers are read from three places — the grammar's own repetition
        // bounds below, the prompt text telling the model its limit, and the worst-case
        // arithmetic that sizes MAX_TOKENS — so there is exactly one place to change them
        // and nowhere for the three to quietly disagree again.
        //
        // Bounded at all, rather than left open, because an unbounded list is what caused
        // the *other* failure mode this file has seen: a 2B model reading a one-page letter
        // emitted fourteen entities and was still going when it hit the token limit,
        // producing text that was valid right up to the point it stopped and therefore
        // unparseable. A grammar that cannot run away also produces a better answer, since
        // the model has to choose what matters instead of listing every capitalised phrase.
        internal const val MAX_ENTITIES = 8
        internal const val MAX_FACTS = 10

        // ── Worst-case answer size, in characters ──
        //
        // A grammar constrains *shape*: it cannot bound how long `name` or `relation` gets,
        // since `string` is `char*` with no length limit, and it cannot bound how much
        // whitespace the model spends on `ws`. So "worst case" below is not the absolute
        // worst the grammar permits — that is unbounded — it is a realistic ceiling built
        // from the longest content this schema actually calls for, lightly spaced the way
        // every model tried on device formats its answers (see `goodAnswer` in
        // AiExtractionUseCaseTest). A model that ignores realistic field lengths entirely
        // could still overflow this; see the report for that residual risk.
        //
        // One maximal entity, e.g.:
        //   {"name": "Bundesagentur für Arbeit Regionaldirektion Sachsen",
        //    "kind": "AUTHORITY", "role": "SENDER_CONTACT",
        //    "relation": "authorized representative of the recipient", "confidence": 0.95}
        //   = 187 characters, rounded up for safety margin.
        internal const val MAX_ENTITY_JSON_CHARS = 190

        // One maximal fact, e.g.:
        //   {"label": "Bedarfsgemeinschaftsnummer laut Bescheid",
        //    "value": "DE02 1203 0000 0000 2020 51 aktuell",
        //    "kind": "REFERENCE", "confidence": 0.95}
        //   = 142 characters, rounded up for safety margin.
        internal const val MAX_FACT_JSON_CHARS = 145

        // `language`, `documentType`, `subject` (each a realistic-length string), plus the
        // object braces, the two key names, and the array brackets around entities/facts.
        // Measured example totals 167 characters; rounded up for safety margin.
        internal const val WRAPPER_JSON_CHARS = 170

        /**
         * The worst case this grammar is sized against: the wrapper, plus [MAX_ENTITIES]
         * maximal entities separated by ", ", plus [MAX_FACTS] maximal facts separated by
         * ", " — two characters per separator, matching the lightly-spaced formatting the
         * examples above use.
         *
         *   170 + (8 × 190 + 7 × 2) + (10 × 145 + 9 × 2) = 3,172 characters ≈ 1,058 tokens
         *
         * at [CHARS_PER_TOKEN]. See [AiExtractionUseCaseTest] for the test that fails the
         * build if [MAX_TOKENS] stops covering this with headroom — which is what actually
         * enforces the relationship; this is arithmetic, not the guardrail.
         */
        internal const val MAX_ANSWER_CHARS = WRAPPER_JSON_CHARS +
            (MAX_ENTITIES * MAX_ENTITY_JSON_CHARS + (MAX_ENTITIES - 1) * 2) +
            (MAX_FACTS * MAX_FACT_JSON_CHARS + (MAX_FACTS - 1) * 2)

        /**
         * Tokens needed to emit [MAX_ANSWER_CHARS] characters, rounding up so the estimate
         * never under-counts.
         */
        private const val MIN_TOKENS_FOR_MAX_ANSWER =
            (MAX_ANSWER_CHARS + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

        /**
         * [MIN_TOKENS_FOR_MAX_ANSWER] plus 25% headroom, for the formatting variance the
         * character estimate above cannot see — extra `ws`, a slightly longer name than the
         * example. This is the fix for the defect this file shipped with: the grammar's own
         * worst case (≈1,058 tokens) did not fit inside the previous MAX_TOKENS of 768, so a
         * legal, complete-by-the-grammar answer could still be truncated and discarded
         * whole. Deriving it from [MAX_ANSWER_CHARS] rather than writing a number means
         * raising [MAX_ENTITIES] or [MAX_FACTS] later moves this too, instead of quietly
         * reopening the same gap.
         *
         * Trade made instead of shrinking the grammar: the device profile this budgets
         * against is the 4096-token cap in `CatalogActiveModelProvider`, and a one-page
         * letter's layout description is a few thousand characters regardless — see
         * [characterBudget]. At 4096 there is comfortable room left over for the page even
         * with this budget; the grammar's entity/fact ceilings were kept because 8 entities
         * and 10 facts is not an unreasonable amount of real content for one letter, and
         * cutting them would trade correctness on ordinary letters to protect a 2048-token
         * device tier that is already a degraded fallback by design (see
         * `CatalogActiveModelProvider.affordableContext`).
         */
        internal const val MAX_TOKENS = MIN_TOKENS_FOR_MAX_ANSWER + MIN_TOKENS_FOR_MAX_ANSWER / 4

        // The prompt used to spend a paragraph asking the model to vary its confidence and
        // mean it ("0.9+ only when...", "Below 0.5 when guessing..."). Removed: on every
        // model tried on device the answer was 0.9 for almost every field regardless of what
        // this section asked for, so it was tokens spent asking for something we now discard
        // and replace with ExtractionConfidence's derived score. The grammar still requires
        // the model to emit a `confidence` number — that cannot change without touching the
        // GBNF — but nothing downstream reads it any more.
        //
        // Declared before SYSTEM_PROMPT_TOKENS, which reads its length: companion object
        // properties initialise in declaration order, so the reverse order would compile
        // but hand SYSTEM_PROMPT_TOKENS an empty string.
        internal val SYSTEM_PROMPT = """
            You read scanned business letters and return structured data as JSON.

            Each input line is one block of text from the page, prefixed with where it sits:
            [zone @ x%,y%]. The zone tells you what a block is for, and you must use it.

            ROLES — decide from the zone, not from wording:
            - The organisation in "header left" or "header right" is the SENDER. It is not
              MENTIONED. A letter always has a sender.
            - The person in "address block" is the RECIPIENT. Skip the salutation: "Frau",
              "Herr", "Sehr geehrte" are titles, not names.
            - A person in "footer", or after "i. A.", or given as a contact for questions, is
              SENDER_CONTACT.
            - Only people named inside the body text are MENTIONED. Use relation to say how
              they relate to the recipient, for example "spouse of the recipient".

            FACTS — copy values exactly as they appear:
            - label: the words used on the page, such as "Aktenzeichen", "Ihr Zeichen",
              "Regelleistung". Never use the kind as the label.
            - Copy digits character by character. Do not adjust a year or reformat a date.
            - DEADLINE is a date the recipient must act by. DATE is the letter's own date.
            - AMOUNT is a sum of money. IBAN is a bank account beginning with two letters,
              such as DE02. A telephone number is neither — it is OTHER.

            List only what matters: the sender, the recipient, any named contact, and people
            actually named in the body. At most $MAX_ENTITIES entities and $MAX_FACTS facts.
            Do not repeat an entity. Do not invent anything that is not on the page.
        """.trimIndent()

        /**
         * Measured, not guessed: `SYSTEM_PROMPT.length` at [CHARS_PER_TOKEN], rounded up.
         * The constant this replaced was a hardcoded 320 — someone's guess, never checked
         * against the prompt it was meant to describe. The real prompt is 1,464 characters,
         * which is ≈488 tokens: 53% more than the guess. That gap was silently eating into
         * [characterBudget]'s idea of how much room the page had, in the same direction as
         * the token-budget defect this file was fixed for. Computed from the prompt so it
         * cannot drift back out of sync when the prompt text changes.
         */
        internal val SYSTEM_PROMPT_TOKENS: Int =
            (SYSTEM_PROMPT.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

        /**
         * GBNF constraining the reply to exactly the shape of `DocumentUnderstanding`.
         *
         * Enum members are literal alternatives, so the model cannot coin a role that does
         * not exist and force the parser to guess. Confidence is restricted to one or two
         * decimals, which is all the precision the value carries.
         *
         * The list lengths are **bounded**, at [MAX_ENTITIES] and [MAX_FACTS] — see the
         * comment on those constants for why, and [MAX_TOKENS] for the arithmetic that
         * keeps this in sync with the token budget generation is cut off at.
         */
        val GRAMMAR = """
            root ::= "{" ws "\"language\":" ws string "," ws "\"documentType\":" ws string "," ws "\"subject\":" ws string "," ws "\"entities\":" ws entities "," ws "\"facts\":" ws facts ws "}"

            entities ::= "[" ws (entity (ws "," ws entity){0,${MAX_ENTITIES - 1}})? ws "]"
            entity ::= "{" ws "\"name\":" ws string "," ws "\"kind\":" ws kind "," ws "\"role\":" ws role "," ws "\"relation\":" ws string "," ws "\"confidence\":" ws conf ws "}"

            facts ::= "[" ws (fact (ws "," ws fact){0,${MAX_FACTS - 1}})? ws "]"
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
