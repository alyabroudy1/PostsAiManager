package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.actions.ActionDates
import com.postsaimanager.core.domain.extraction.actions.ActionKindProfile
import com.postsaimanager.core.domain.extraction.actions.ActionKindReader
import com.postsaimanager.core.domain.extraction.actions.ActionScorer
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.TicketSlot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

/**
 * One device recording of the action scores of one letter (`benchmark/actions/<key>.json`, written by `ActionKindBenchmarkTest`): the
 * stored slot values the scorer was given, whether a sender was stored, and every action question it scored with its raw log-odds.
 */
class ActionRecording(val key: String, val slots: List<TicketSlot>, val senderStored: Boolean, val scores: Map<String, Double>) {

    /** Replays the recorded scores; a question the recording does not hold is an error, never a quiet zero. */
    val scorer = ActionScorer { _, questions ->
        questions.map { q -> scores[q] ?: error("the recording of $key has no score for «$q»; record it again on the device") }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val SEPARATOR = "\n@@\n"

        fun load(dir: File = File("src/test/resources/benchmark/actions")): List<ActionRecording> =
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.mapNotNull { parse(it.readText()) }

        fun parse(text: String): ActionRecording? {
            val o = json.parseToJsonElement(text).jsonObject
            if (o["error"] != null) return null
            val key = o["key"]?.jsonPrimitive?.contentOrNull ?: return null
            val slots = (o["slots"] as? JsonArray).orEmpty().map { s ->
                val so = s.jsonObject
                TicketSlot(so.getValue("key").jsonPrimitive.content, so.getValue("label").jsonPrimitive.content, so.getValue("value").jsonPrimitive.content)
            }
            val scores = LinkedHashMap<String, Double>()
            for (ask in o.getValue("asks").jsonArray) {
                val a = ask.jsonObject
                val answer = a["answer"]?.jsonPrimitive?.contentOrNull ?: continue
                val questions = a.getValue("question").jsonPrimitive.content.split(SEPARATOR)
                val values = answer.split(',').map { it.trim().toDouble() }
                check(questions.size == values.size) { "$key: ${questions.size} questions, ${values.size} scores" }
                questions.zip(values).forEach { (q, v) -> scores[q] = v }
            }
            return ActionRecording(key, slots, o["senderStored"]?.jsonPrimitive?.contentOrNull == "true", scores)
        }
    }
}

/** What one letter's replay chose, with what the manifest says it should. */
class ActionOutcome(val doc: ManifestDoc, val slots: List<TicketSlot>, val items: List<ActionItem>) {
    val expected: List<ExpectedAction> get() = doc.actions.orEmpty()
    val expectedKinds: Set<String> get() = expected.map { it.kind }.toSet()
    val shownKinds: List<String> get() = items.map { it.kind }
    private val allowed: Set<String> get() = expectedKinds + doc.actionsAlsoOk

    /** Kinds shown that the letter does not ask for (and that are not marked as acceptable). */
    val wrongKinds: List<String> get() = shownKinds.filter { it !in allowed }

    /** The shown kinds without the acceptable extras: what is compared with what the letter asks. */
    val shownCore: Set<String> get() = shownKinds.filter { it !in doc.actionsAlsoOk || it in expectedKinds }.toSet()

    private fun slotValue(key: String?) = slots.firstOrNull { it.key == key }?.value

    /** (right, asked) of the date bindings of the shown actions the letter asks for: a bound date must be the expected one, and none bound is right when none is expected. */
    fun dateBindings(): Pair<Int, Int> = bindings { e, item ->
        val bound = slotValue(item.bindings["date"])?.let { ActionDates.read(it)?.date?.toString() }
        bound == e.date
    }

    fun amountBindings(): Pair<Int, Int> = bindings { e, item ->
        val bound = slotValue(item.bindings["amount"])?.let(::digits)
        bound == e.amount?.let(::digits)
    }

    private fun bindings(right: (ExpectedAction, ActionItem) -> Boolean): Pair<Int, Int> {
        val matched = items.mapNotNull { item -> expected.firstOrNull { it.kind == item.kind }?.let { it to item } }
        return matched.count { (e, item) -> right(e, item) } to matched.size
    }

    private fun digits(text: String) = text.filter { it.isDigit() }
}

/** The numbers of a replay over the letters that have a recording and an annotated manifest. */
class ActionReport(val outcomes: List<ActionOutcome>) {
    private val noAction = outcomes.filter { it.expected.isEmpty() }
    private val predictedNone = outcomes.filter { it.items.isEmpty() }

    val letters: Int get() = outcomes.size
    val noActionLetters: Int get() = noAction.size
    val noActionRecall: Double get() = ratio(noAction.count { it.items.isEmpty() }, noAction.size)
    val noActionPrecision: Double get() = ratio(predictedNone.count { it.expected.isEmpty() }, predictedNone.size)

    private val asking = outcomes.filter { it.expected.isNotEmpty() }
    val top1: Double get() = ratio(asking.count { it.shownKinds.firstOrNull() in it.expectedKinds }, asking.size)
    val setMatch: Double get() = ratio(outcomes.count { it.shownCore == it.expectedKinds }, outcomes.size)

    val shown: Int get() = outcomes.sumOf { it.items.size }
    val wrong: Int get() = outcomes.sumOf { it.wrongKinds.size }
    val wrongRate: Double get() = ratio(wrong, shown)
    val lettersWithWrong: Int get() = outcomes.count { it.wrongKinds.isNotEmpty() }

    val dateAccuracy: Double get() = outcomes.map { it.dateBindings() }.let { ratio(it.sumOf { p -> p.first }, it.sumOf { p -> p.second }) }
    val amountAccuracy: Double get() = outcomes.map { it.amountBindings() }.let { ratio(it.sumOf { p -> p.first }, it.sumOf { p -> p.second }) }
    val bindingsAsked: Int get() = outcomes.sumOf { it.dateBindings().second }

    private fun ratio(a: Int, b: Int) = if (b == 0) 1.0 else a.toDouble() / b

    fun table(): String = String.format(
        Locale.ROOT,
        "letters=%d noAction(n=%d) precision=%.2f recall=%.2f | top1=%.2f setMatch=%.2f | shown=%d wrong=%d (%.2f) lettersWithWrong=%d | date=%.2f amount=%.2f (n=%d)",
        letters, noActionLetters, noActionPrecision, noActionRecall, top1, setMatch, shown, wrong, wrongRate, lettersWithWrong, dateAccuracy, amountAccuracy, bindingsAsked,
    )
}

/** Replays the recorded scores through the real reader under a profile and scores the choice against the manifests. */
object ActionKindEval {

    fun docs(): Map<String, ManifestDoc> = BenchmarkFixtures.load().docs.map { it.first }.filter { it.actions != null }.associateBy { it.key }

    fun run(recordings: List<ActionRecording>, profile: ActionKindProfile): ActionReport {
        val docs = docs()
        return ActionReport(
            recordings.mapNotNull { rec ->
                val doc = docs[rec.key] ?: return@mapNotNull null
                val reading = runBlocking { ActionKindReader(rec.scorer, profile).read(rec.slots, rec.senderStored) }
                ActionOutcome(doc, rec.slots, reading?.items.orEmpty())
            },
        )
    }
}
