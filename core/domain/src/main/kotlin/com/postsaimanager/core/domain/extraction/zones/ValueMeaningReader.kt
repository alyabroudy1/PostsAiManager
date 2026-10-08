package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.ValueMeaning
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings
import java.util.Locale

/**
 * "What does this date mean?" and "what is this amount?", asked once per date and amount the reading kept, in the letter's open session.
 *
 * Every meaning of the value's kind ([ValueMeanings]) is one statement scored about the value in one batch (the zone block and the value's
 * head are the shared level, so the batch costs one statement per meaning). The same statements are scored about a made-up value that is
 * nowhere in the letter (the content-free baseline, [ScoringDescriptions.DATE_BASELINE_VALUE]) over the same zone block, once for every
 * block and kind the values come from. A meaning counts only when its score beats its baseline by more than its margin
 * ([ScoringProfile.meaningMargin]); the best of those is the answer, and when none counts the answer is "other" (no meaning). The model
 * only scores; the registry and the margins decide.
 *
 * It never CHOOSES a value for a slot, but it can veto one (see [MeaningVerdict]): the slot keeps the value it chose and the meaning is
 * attached to it, unless the meaning contradicts what the slot is for (a due-date slot holding a value that means "first day of a period"),
 * in which case the slot is left empty; it is never given another value by a rule.
 *
 * @param score scores [questions] about [shared] in the open session: the interpreter's own scorer (null when the engine failed the batch)
 */
class ValueMeaningReader(
    private val score: suspend (name: String, shared: String, questions: List<String>) -> List<Double>?,
    private val profile: ScoringProfile,
    private val registry: ValueMeanings = ValueMeanings.DEFAULT,
    private val trace: (String) -> Unit = {},
) {

    /**
     * A value to name.
     *
     * @property key what the answer is returned under (the slot that holds the value)
     * @property printed the value as printed
     * @property context the row it is printed in and the rows around it
     * @property block the zone block the value was asked about, which its baseline is asked over too
     */
    class Target(val key: String, val kind: MeaningKind, val printed: String, val context: ZonedLetter.Context?, val block: String)

    /** The meaning of each [targets] value that has one, by [Target.key]; a value with none ("other") is absent. */
    suspend fun read(targets: List<Target>): Map<String, ValueMeaning> {
        val baselines = HashMap<Pair<MeaningKind, String>, List<Double>?>()
        val answers = LinkedHashMap<String, ValueMeaning>()
        for (t in targets) {
            val meanings = registry.of(t.kind)
            if (meanings.isEmpty()) continue
            val statements = meanings.map { ZonePrompt.scoringAsk(it.description) }
            val base = baselines.getOrPut(t.kind to t.block) {
                score("baseline:meaning:${t.kind.name.lowercase()}", t.block + ZonePrompt.scoringHead(probe(t.kind), null), statements)
            } ?: continue
            val scores = score("meaning:${t.kind.name.lowercase()}", t.block + ZonePrompt.scoringHead(t.printed, t.context), statements) ?: continue
            val gaps = meanings.indices.map { scores[it] - base[it] }
            val best = meanings.indices.filter { gaps[it] > profile.meaningMargin(meanings[it].id) }.maxByOrNull { gaps[it] }
            trace(
                if (best == null) {
                    "meaning ${t.key} -> other"
                } else {
                    String.format(Locale.ROOT, "meaning %s -> %s gap=%+.2f", t.key, meanings[best].id, gaps[best])
                },
            )
            if (best != null) answers[t.key] = meanings[best]
        }
        return answers
    }

    private fun probe(kind: MeaningKind): String = when (kind) {
        MeaningKind.DATE -> ScoringDescriptions.DATE_BASELINE_VALUE
        MeaningKind.AMOUNT -> ScoringDescriptions.AMOUNT_BASELINE_VALUE
    }
}
