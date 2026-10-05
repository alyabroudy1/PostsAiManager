package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.LegacyTypes

/**
 * Lets the recordings made before extraction-v2-2 (variants `zonesscoring`, `zonesscoringctx`, `zonesscoring2b`) stand in for the
 * family and topic scores they never recorded, explicitly and in one place.
 *
 * Those recordings hold one score batch `score:type`, one score per legacy type; the interpreter now asks one `score:family` batch
 * (every family, then every topic). Until a letter is recorded again on the device (variant `zonesscoring3`, which holds the real
 * `score:family` batch), the replay answers the family batch from the legacy one, through [LegacyTypes], the one owner of that mapping:
 *
 * - a family scores the best score of the legacy types that map onto it (`invoice_bill` the better of `bill` and `reminder_dunning`);
 *   a family no legacy type maps onto scores [NO_LEGACY_TYPE], so it never wins;
 * - the topics are those of the legacy type that scored best ([LegacyTypes.Mapping.topics]), [TOPIC_YES] for them and [TOPIC_NO] for the rest.
 *
 * Nothing else is bridged: every question with no recording (the address labels, the summary, a slot nobody asked under the old type)
 * is a miss the replay lists (see [ReplayPromptSession.misses]), and a recording that holds a real `score:family` batch is replayed
 * strictly.
 */
internal object LegacyFamilyBridge {

    /** The legacy types of the old schema in its order, with the directions that offered them (what the recorded batch was made of). */
    private class Legacy(val id: String, val incoming: Boolean)

    private val ALL = listOf(
        Legacy("bill", true), Legacy("reminder_dunning", true), Legacy("authority_tax", true), Legacy("health", true),
        Legacy("insurance_contract", true), Legacy("school", true), Legacy("receipt", true), Legacy("info_no_action", true),
        Legacy("outgoing_letter", false), Legacy("payment_proof", false), Legacy("other", true),
    )

    /** The legacy type ids of the recorded `score:type` batch of [size] scores (all types, or the ones an incoming document is offered). */
    fun legacyIds(size: Int): List<String> = when (size) {
        ALL.size -> ALL.map { it.id }
        ALL.count { it.incoming } -> ALL.filter { it.incoming }.map { it.id }
        else -> error("the recording was made with $size types, which is neither the ${ALL.size} nor the ${ALL.count { it.incoming }} legacy types")
    }

    /**
     * Topics a legacy type is scripted to have beyond [LegacyTypes]'s: the old `authority_tax` read the tax number, which belongs to the
     * topic `tax` now, so a replayed `authority_tax` letter has it and asks what the old type asked.
     */
    private val EXTRA_TOPICS = mapOf("authority_tax" to listOf("tax"))

    /** What a family scores when no legacy type maps onto it. */
    const val NO_LEGACY_TYPE = -12.0

    /**
     * The score a legacy replay gives every candidate of a question its recording never held: at or below every threshold of the shipped
     * profile (the default is -12.0 and a candidate is taken only above it), so such a question takes nothing.
     */
    const val NOT_RECORDED = -12.0

    /** The scores a topic gets from the legacy type's topics. */
    const val TOPIC_YES = 2.0
    const val TOPIC_NO = -2.0

    /** The legacy view of one recording's `score:type` batch: a score per family and the topics of the best legacy type. */
    class View(val familyScores: Map<String, Double>, val topics: Set<String>, val bestLegacyId: String)

    /** The view of [recording], or null when it holds no `score:type` batch (a recording made for the new interpreter, or a generated one). */
    fun viewOf(recording: Recording): View? {
        val ask = recording.asks.firstOrNull { it.name == "score:type" } ?: return null
        val scores = ask.answer?.split(',')?.map { it.trim().toDouble() } ?: return null
        val ids = legacyIds(scores.size)
        val byFamily = HashMap<String, Double>()
        ids.forEachIndexed { i, legacy -> LegacyTypes.of(legacy)?.family?.let { byFamily.merge(it, scores[i], ::maxOf) } }
        // The best of the types an incoming document is offered (a recording made before the direction fix scored all of them).
        val incoming = ALL.filter { it.incoming }.map { it.id }
        val best = scores.indices.filter { ids[it] in incoming }.maxByOrNull { scores[it] }?.let { ids[it] } ?: ids.first()
        val topics = LegacyTypes.of(best)?.topics.orEmpty() + EXTRA_TOPICS[best].orEmpty()
        return View(byFamily, topics.toSet(), best)
    }

    /**
     * The scores of a live family/topic batch (`Is this document <family>? Answer:` per family, `Does this document concern <topic>? Answer:`
     * per topic), read from [view]; null when [questions] are not such a batch.
     */
    fun answer(view: View, questions: List<String>): List<Double>? {
        val schema = ExtractionSchema.DEFAULT
        val out = ArrayList<Double>()
        for (q in questions) {
            val text = q.trim()
            val family = schema.families.firstOrNull { text.startsWith("Is this document ${it.description}? Answer:") }
            val topic = schema.topics.firstOrNull { text.startsWith("Does this document concern ${it.description}? Answer:") }
            out += when {
                family != null -> view.familyScores[family.id] ?: NO_LEGACY_TYPE
                topic != null -> if (topic.id in view.topics) TOPIC_YES else TOPIC_NO
                else -> return null
            }
        }
        return out
    }
}
