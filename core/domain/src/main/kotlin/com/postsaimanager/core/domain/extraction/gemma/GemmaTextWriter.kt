package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.ModelUse
import com.postsaimanager.core.domain.ai.SamplingPurpose
import com.postsaimanager.core.domain.ai.samplingFor
import com.postsaimanager.core.domain.ai.loadForUse
import com.postsaimanager.core.domain.ai.StructuredRequest
import com.postsaimanager.core.domain.extraction.text.KeyInfoFormat
import com.postsaimanager.core.domain.extraction.text.KeyInfoVerifier
import com.postsaimanager.core.domain.extraction.text.SummaryFacts
import com.postsaimanager.core.domain.extraction.text.SummaryGate
import com.postsaimanager.core.domain.extraction.text.SummaryLimits
import com.postsaimanager.core.domain.extraction.text.SummaryResult
import com.postsaimanager.core.domain.extraction.text.SummaryWriter
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.model.SummarySource
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject

/** What the text step is asked about one stored reading. */
class GemmaTextRequest(
    /** The letter's text: the grounding reference of both texts, and what the model reads. */
    val ocrText: String,
    /** The verified facts the reading stored (sender, amount, dates, subject ...): what the summary may rest on. */
    val facts: SummaryFacts,
    /** The values the reading already holds, as stored: a key fact that repeats one of them is dropped. */
    val knownValues: List<String>,
    /** The document's language as a BCP-47 code, or null when it is not known. */
    val languageCode: String?,
    /** The reader's answer to "has it been paid already?", so the summary never asks for a payment that was made. */
    val paid: PaidState?,
    /** False when the summary was written already (the reading's first turn): only the key facts are asked. */
    val writeSummary: Boolean = true,
)

sealed interface GemmaTextOutcome {

    /** [summary] always exists (the model's sentences, or the template when none passed its check); [keyInfo] may be empty. */
    class Written(
        val summary: SummaryResult,
        val keyInfo: List<KeyInfoVerifier.Kept>,
        val ms: Long,
        val notes: List<String>,
        /** False when no summary was asked ([GemmaTextRequest.writeSummary]): [summary] is then only the template and is not to be stored. */
        val summaryAsked: Boolean = true,
    ) : GemmaTextOutcome

    /** The step could not run (no model, busy, failed or too slow): nothing was written, and it is asked again later. */
    class Unavailable(val reason: String) : GemmaTextOutcome
}

/** The port the text step generates through: one answer constrained to a JSON schema, null when the model could not give one. */
interface GemmaTextGenerator {
    suspend fun generate(system: String, prompt: String, schema: String, maxTokens: Int): String?
}

/**
 * The second, lower-priority step of a Gemma reading: the summary and the key facts, written after the reading is stored (the document is
 * usable at once, and the screens say "Summary coming..." meanwhile).
 *
 * They are the long free texts, so they are what made the one-call answer slow to write; here they are one small answer of their own. The
 * model sees the letter's text and the facts the reading stored (not the pictures), is told whether the document was paid already
 * ([PaidState]), and writes in the document's language. Code only verifies, with the verifiers every other text passes: [SummaryGate] (numbers and names must be in the letter, no
 * copied line; a rejected summary is asked once more with an instruction not to copy, then settles on the template summary of the
 * verified fields) and [KeyInfoVerifier] (a value is kept only when it is in the letter and not a repeat of a read field).
 */
class GemmaTextWriter @Inject constructor(
    private val generator: GemmaTextGenerator,
) {

    private val gate = SummaryGate()
    private val keyInfos = KeyInfoVerifier()

    suspend fun write(request: GemmaTextRequest): GemmaTextOutcome {
        val started = System.nanoTime()
        val notes = mutableListOf<String>()
        val schema = schema(request.writeSummary)
        var keyInfo: List<KeyInfoVerifier.Kept>? = null
        for (attempt in 0 until SummaryWriter.MAX_ASKS) {
            val answer = generator.generate(SYSTEM, prompt(request, antiCopy = attempt > 0), schema, maxTokens(request.writeSummary))
            if (answer == null) {
                if (attempt == 0) return GemmaTextOutcome.Unavailable("no answer (the model is busy, the run failed or it took too long)")
                break
            }
            // An answer cut off at the token cap is not lost: the complete facts before the cut are kept (the verifiers check them as usual).
            val parsed = parse(answer) ?: PartialAnswer.salvage(answer)?.also {
                notes += "attempt ${attempt + 1}: the answer was cut off; ${it.second.size} complete fact(s) kept"
            }
            if (parsed == null) {
                notes += "attempt ${attempt + 1}: the answer could not be read"
                continue
            }
            if (keyInfo == null) {
                val facts = parsed.second.map { KeyInfoFormat.Fact(it.first, it.second) }
                val report = keyInfos.report(facts, request.ocrText, request.facts.values() + request.knownValues)
                keyInfo = report.kept
                // The reasons are logged (labels and reason codes only, never a value of the letter): no key facts is either a model that
                // listed none or facts the verifier dropped, and the log must tell which.
                notes += "key facts: the model listed ${facts.size}, kept ${report.kept.size}" +
                    report.dropped.joinToString(prefix = if (report.dropped.isEmpty()) "" else ", dropped [", postfix = if (report.dropped.isEmpty()) "" else "]") { "${it.label}: ${it.reason}" }
            }
            // The summary was written by the reading's first turn: only the key facts were asked, and nothing of a summary is stored here.
            if (!request.writeSummary) {
                return GemmaTextOutcome.Written(SummaryWriter.templateOf(request.facts), keyInfo.orEmpty(), msSince(started), notes, summaryAsked = false)
            }
            when (val verdict = gate.check(parsed.first, request.ocrText, request.facts.values() + request.knownValues)) {
                is SummaryGate.Verdict.Accepted ->
                    return GemmaTextOutcome.Written(SummaryResult(verdict.text, SummarySource.MODEL, null, emptyList()), keyInfo.orEmpty(), msSince(started), notes)
                is SummaryGate.Verdict.Rejected -> notes += "the summary was rejected: ${verdict.reason}"
            }
        }
        return GemmaTextOutcome.Written(SummaryWriter.templateOf(request.facts), keyInfo.orEmpty(), msSince(started), notes + "the template summary was used")
    }

    internal fun prompt(request: GemmaTextRequest, antiCopy: Boolean): String = buildString {
        append("LETTER:\n").append(request.ocrText.take(MAX_LETTER_CHARS)).append("\n\n")
        append("FACTS (verified; use only these and the letter):\n")
        val entries = request.facts.entries()
        if (entries.isEmpty()) append("- none\n")
        entries.forEach { (role, value) -> append("- ").append(role).append(": ").append(value).append('\n') }
        request.paid?.let { append("\nPAYMENT: ").append(it.sentence).append(".\n") }
        append(if (request.writeSummary) "\nANSWER the JSON object with two keys.\n" else "\nANSWER the JSON object with one key.\n")
        if (request.writeSummary) {
            append("- ${KEY_SUMMARY}: one or two sentences, at most ${SummaryWriter.MAX_WORDS} words and at most ${SummaryLimits.MAX_CHARS} characters, saying what the reader must know or do. Use only the facts and the letter. ")
            append(request.languageCode?.trim()?.takeIf { it.isNotEmpty() }?.let { "Write in the language with the code \"$it\". " } ?: "Write it in the letter's own language. ")
            if (request.paid == PaidState.ALREADY_PAID) append("The document says everything is already paid: never ask the reader to pay. ")
            if (antiCopy) append("Do not copy any line of the letter; put it in your own words. ")
            append('\n')
        }
        append("- ${KEY_FACTS}: up to $MAX_FACTS other facts a person would need from THIS document that are not among the facts above, ")
        append("each {${KEY_LABEL}: a label of one to four words without digits or full stops")
        request.languageCode?.trim()?.takeIf { it.isNotEmpty() }?.let { append(", in the language with the code \"$it\"") }
        append(", ${KEY_VALUE}: the value copied exactly as printed}. ")
        append("An empty list when there is nothing more.")
    }

    private fun schema(withSummary: Boolean): String = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            JsonObject(
                linkedMapOf<String, JsonElement>().apply {
                    if (withSummary) put(KEY_SUMMARY, buildJsonObject { put("type", "string"); put("maxLength", SummaryLimits.MAX_CHARS) })
                    put(
                    KEY_FACTS, buildJsonObject {
                        put("type", "array")
                        put("maxItems", MAX_FACTS)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put(KEY_LABEL, buildJsonObject { put("type", "string"); put("maxLength", KeyInfoFormat.MAX_LABEL_CHARS) })
                                        put(KEY_VALUE, buildJsonObject { put("type", "string"); put("maxLength", KeyInfoFormat.MAX_VALUE_CHARS) })
                                    },
                                )
                                putJsonArray("required") { add(JsonPrimitive(KEY_LABEL)); add(JsonPrimitive(KEY_VALUE)) }
                                put("additionalProperties", false)
                            },
                        )
                    },
                    )
                },
            ),
        )
        putJsonArray("required") { if (withSummary) add(JsonPrimitive(KEY_SUMMARY)); add(JsonPrimitive(KEY_FACTS)) }
        put("additionalProperties", false)
    }.toString()

    /** The summary and the (label, value) facts of [answer]; null when it is no JSON object. */
    private fun parse(answer: String): Pair<String, List<Pair<String, String>>>? {
        val root = runCatching { Json { isLenient = true }.parseToJsonElement(answer.trim()).jsonObject }.getOrNull() ?: return null
        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val facts = ((root[KEY_FACTS] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.map { it.str(KEY_LABEL) to it.str(KEY_VALUE) }
        return root.str(KEY_SUMMARY) to facts
    }

    private fun msSince(started: Long) = (System.nanoTime() - started) / NANOS_PER_MS

    companion object {
        private const val SYSTEM = "You write two short texts about one letter and answer with JSON only. Never invent a name, a number or a date: use what the letter and the facts say."
        const val KEY_SUMMARY = "s"
        const val KEY_FACTS = "k"
        const val KEY_LABEL = "l"
        const val KEY_VALUE = "v"
        /** A tight schema: at most this many facts, a label of at most [KeyInfoFormat.MAX_LABEL_CHARS] and a value of at most [KeyInfoFormat.MAX_VALUE_CHARS] characters. */
        const val MAX_FACTS = KeyInfoFormat.MAX_FACTS

        /** Up to six facts of about 30 tokens each, with room to spare; a looping answer ends here and its complete facts are salvaged. */
        const val FACTS_TOKENS = 400

        /** The decode budget of the step: the facts, plus the summary's own budget when the summary is asked too. */
        fun maxTokens(withSummary: Boolean): Int = FACTS_TOKENS + if (withSummary) SummaryLimits.MAX_TOKENS else 0
        const val MAX_LETTER_CHARS = 6_000
        const val NANOS_PER_MS = 1_000_000L
    }
}

/**
 * [GemmaTextGenerator] over the chat model, as [ChatEngineGemmaReader] reads: the LiteRT-LM Gemma loaded through the same [ChatEngine],
 * thinking off, no pictures. Null when no chat model is installed, it is not a LiteRT-LM model, it cannot be loaded, it is busy with a
 * reply or another reading, or the answer did not come within [TIMEOUT_MS].
 */
class ChatEngineGemmaTextGenerator @Inject constructor(
    private val engine: ChatEngine,
    private val activeModel: ActiveModelProvider,
) : GemmaTextGenerator {

    override suspend fun generate(system: String, prompt: String, schema: String, maxTokens: Int): String? {
        val path = activeModel.activeModelPath() ?: return null
        val config = activeModel.readingModelConfig()
        if (config.runtime != ModelRuntime.LITERT_LM) return null
        if (engine.loadForUse(ModelUse.READING, path, config) is PamResult.Error) return null
        return withTimeoutOrNull(TIMEOUT_MS + GRACE_MS) { engine.generateStructured(gemmaTextRequest(system, prompt, schema, maxTokens)) }
    }

    private companion object {
        const val GRACE_MS = 15_000L
    }
}

private const val TIMEOUT_MS = 90_000L

/**
 * The text step's one request. The facts and the second summary are written text, not a pick from a list: the normal sampler
 * ([SamplingPurpose.FREE_TEXT]). Greedy decoding of a long free value loops on a repeated token until the token cap (plan 20, pass 27).
 */
internal fun gemmaTextRequest(system: String, prompt: String, schema: String, maxTokens: Int): StructuredRequest {
    val sampling = samplingFor(SamplingPurpose.FREE_TEXT)
    return StructuredRequest(
        system = system, prompt = prompt, schema = schema, maxTokens = maxTokens, timeoutMs = TIMEOUT_MS,
        temperature = sampling.temperature, topK = sampling.topK, topP = sampling.topP,
    )
}
