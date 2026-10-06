package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.actions.ActionKindProfile
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import org.junit.jupiter.api.Test

/**
 * The recorded action scores of the 16 benchmark letters (device run of `ActionKindBenchmarkTest`, Qwen3.5-0.8B) replayed through the real
 * [com.postsaimanager.core.domain.extraction.actions.ActionKindReader] under the shipped thresholds, against what each manifest says the
 * letter asks. Pins the results the thresholds were chosen for, like the decoder's golden file: a change to a threshold, a question or the
 * selection that moves them is a decision to take on purpose, with the numbers of `ActionKindTuneTest` in front of you.
 *
 * What the numbers say (see [ModelProfiles.QWEN35_08B]): the model leans Yes on most kinds, so the raw sign is no decision, and the best
 * kind above a floor with its close neighbours is. A binding can only be as right as the stored fields: three of the eleven expected amounts
 * were not stored by the reading in the first place.
 */
class ActionKindReplayTest {

    private val recordings = ActionRecording.load()
    private val shipped = ModelProfiles.QWEN35_08B.scoring.actions

    @Test
    fun `every benchmark letter has a recording and replays without an unrecorded question`() {
        assertThat(recordings.map { it.key }).containsExactlyElementsIn(ActionKindEval.docs().keys)
        assertThat(recordings).hasSize(16)
        // The replay of the shipped profile asks only questions the recording holds (a missing one is an error, never a zero).
        ActionKindEval.run(recordings, shipped)
    }

    @Test
    fun `the shipped thresholds choose the kinds the letters ask, with a low wrong-action rate`() {
        val r = ActionKindEval.run(recordings, shipped)
        // 13 of the 16 letters show exactly what they ask (the acceptable extras aside), the first kind is right in 10 of the 13 that ask.
        assertThat(r.outcomes.count { it.shownCore == it.expectedKinds }).isEqualTo(13)
        assertThat(r.outcomes.filter { it.expected.isNotEmpty() }.count { it.shownKinds.firstOrNull() in it.expectedKinds }).isEqualTo(10)
        // 16 actions shown, 2 of them wrong: a confirmation for the car insurance letter (it offers a cancellation) and a payment for the receipt.
        assertThat(r.shown).isEqualTo(16)
        assertThat(r.wrong).isEqualTo(2)
        assertThat(r.outcomes.filter { it.wrongKinds.isNotEmpty() }.map { it.doc.key })
            .containsExactly("N2-kfz-verlaengerung-2p", "receipt-noise-1p")
    }

    @Test
    fun `of the three letters that ask nothing two show no action, and the one that does is the receipt`() {
        val r = ActionKindEval.run(recordings, shipped)
        assertThat(r.noActionLetters).isEqualTo(3)
        assertThat(r.outcomes.filter { it.expected.isEmpty() && it.items.isEmpty() }.map { it.doc.key })
            .containsExactly("N8-info-bank-noaction-1p", "degraded-3p")
        // Everything shown as "nothing to do" is right: no letter that asks something is shown as asking nothing.
        assertThat(r.noActionPrecision).isEqualTo(1.0)
        assertThat(r.noActionRecall).isWithin(1e-9).of(2.0 / 3)
    }

    @Test
    fun `the invoice and the car insurance letter of the phone test`() {
        val r = ActionKindEval.run(recordings, shipped).outcomes.associateBy { it.doc.key }
        assertThat(r.getValue("invoice-2p").shownKinds).containsExactly("pay")
        assertThat(r.getValue("N10-kinderarzt-termin-1p").shownKinds).containsExactly("attend")
        assertThat(r.getValue("N8-info-bank-noaction-1p").shownKinds).isEmpty()
    }

    @Test
    fun `the bindings are as right as the stored fields allow`() {
        val r = ActionKindEval.run(recordings, shipped)
        // Dates: 9 of 11 are the date the letter names, 1 was not stored as a date at all (nothing bound), 1 is another stored date.
        assertThat(r.dates.right).isEqualTo(9)
        assertThat(r.dates.wrong).isEqualTo(1)
        assertThat(r.dates.missing).isEqualTo(1)
        assertThat(r.dates.reachable).isEqualTo(11)
        // Amounts: 7 of 11 are right; 3 of the wrong ones are amounts the reading did not store (the total or a fee was taken for it).
        assertThat(r.amounts.right).isEqualTo(7)
        assertThat(r.amounts.wrong).isEqualTo(3)
        assertThat(r.amounts.missing).isEqualTo(1)
        assertThat(r.amounts.reachable).isEqualTo(8)
    }

    @Test
    fun `choosing every kind above zero, the raw sign, shows far more wrong actions`() {
        val raw = ActionKindEval.run(
            recordings, ActionKindProfile(anyThreshold = Double.NEGATIVE_INFINITY, minScore = 0.0, margin = Double.MAX_VALUE, maxActions = 8),
        )
        assertThat(raw.shown).isEqualTo(33)
        assertThat(raw.wrong).isEqualTo(15)
        assertThat(raw.noActionRecall).isWithin(1e-9).of(1.0 / 3)
    }

    @Test
    fun `at most two actions are shown for any letter`() {
        assertThat(ActionKindEval.run(recordings, shipped).outcomes.maxOf { it.items.size }).isAtMost(ActionKindProfile.MAX_ACTIONS)
    }
}
