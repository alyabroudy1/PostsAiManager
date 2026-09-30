package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import org.junit.jupiter.api.Test
import java.util.Random

class SlotDecoderTest {

    private fun name(id: String, score: Double) = ScoredCandidate(CandidateFacts(id, CandidateKind.NAME, id), score)
    private fun date(id: String, iso: String, score: Double) = ScoredCandidate(CandidateFacts(id, CandidateKind.DATE, iso), score)
    private fun amount(id: String, cents: Long, score: Double) =
        ScoredCandidate(CandidateFacts(id, CandidateKind.AMOUNT, "%.2f EUR".format(cents / 100.0), cents, "EUR"), score)

    private fun q(n: String, vararg c: ScoredCandidate, t: Double = -12.0, excludes: String? = null) =
        ScoredQuestion(n, c.toList(), t, excludes)

    private val joint = DecoderSpec(DecoderKind.JOINT).create()

    @Test
    fun `argmax takes each question's best above its threshold and leaves out the sender's pick`() {
        val questions = listOf(
            q("sender", name("M1", 0.9), name("M2", 0.4)),
            q("addressee", name("M1", 0.8), name("M2", 0.1), excludes = "sender"),
            q("slot:total", amount("A1", 100, -0.5), amount("A2", 200, -0.7), t = -0.3),
        )
        assertThat(PerSlotArgmax.decode(questions, emptyList()))
            .containsExactly("sender", "M1", "addressee", "M2", "slot:total", null).inOrder()
    }

    @Test
    fun `a joint assignment gives a contested candidate to the question that needs it more`() {
        // M1 is best for both; the sender loses little by taking M2, the addressee loses a lot.
        val questions = listOf(
            q("sender", name("M1", 0.9), name("M2", 0.8)),
            q("addressee", name("M1", 0.8), name("M2", -0.4)),
        )
        assertThat(PerSlotArgmax.decode(questions, emptyList())["sender"]).isEqualTo("M1")
        val d = joint.decode(questions, emptyList())
        assertThat(d["sender"]).isEqualTo("M2")
        assertThat(d["addressee"]).isEqualTo("M1")
    }

    @Test
    fun `a question goes unanswered when the only candidate left is worth less than abstaining`() {
        val questions = listOf(
            q("sender", name("M1", 0.9)),
            q("care_of", name("M1", 0.5), t = 0.0),
        )
        assertThat(joint.decode(questions, emptyList())).containsExactly("sender", "M1", "care_of", null)
    }

    @Test
    fun `slots that are the same fact under another name may share a candidate`() {
        val questions = listOf(
            q("slot:reference", name("N1", 0.9)),
            q("slot:invoice_no", name("N1", 0.8)),
            q("slot:customer_no", name("N1", 0.7), name("N2", 0.6)),
        )
        val d = joint.decode(questions, emptyList())
        assertThat(d["slot:reference"]).isEqualTo("N1")
        assertThat(d["slot:invoice_no"]).isEqualTo("N1")
        assertThat(d["slot:customer_no"]).isEqualTo("N2")
    }

    @Test
    fun `a due date before the letter date costs the penalty`() {
        val questions = listOf(
            q("slot:letter_date", date("D1", "2026-09-28", 0.5), date("D2", "2026-08-01", 0.4)),
            q("slot:due_date", date("D2", "2026-08-01", 0.6), date("D3", "2026-10-15", 0.5)),
        )
        // Without the penalty the best two are D1 and D2 (D2 is before D1); with it the due date moves to D3.
        val plain = DecoderSpec(DecoderKind.JOINT_CONSTRAINED, sharing = SharingRules.NONE).create().decode(questions, emptyList())
        assertThat(plain["slot:due_date"]).isEqualTo("D2")
        val d = DecoderSpec(DecoderKind.JOINT_CONSTRAINED, dateOrderPenalty = 0.5).create().decode(questions, emptyList())
        assertThat(d["slot:letter_date"]).isEqualTo("D1")
        assertThat(d["slot:due_date"]).isEqualTo("D3")
    }

    @Test
    fun `a total that completes a net plus VAT equals gross triple is preferred to its parts`() {
        val net = amount("A1", 100_000, 0.3)
        val vat = amount("A2", 19_000, 0.3)
        val gross = amount("A3", 119_000, 0.2)
        val pool = listOf(net, vat, gross).map { it.facts }
        val questions = listOf(q("slot:total", net, vat, gross))
        assertThat(DecoderSpec(DecoderKind.JOINT_CONSTRAINED).create().decode(questions, pool)["slot:total"]).isEqualTo("A1")
        assertThat(DecoderSpec(DecoderKind.JOINT_CONSTRAINED, tripleBonus = 0.5).create().decode(questions, pool)["slot:total"]).isEqualTo("A3")
    }

    @Test
    fun `without constraints the joint search equals a brute force on random matrices`() {
        val rnd = Random(7)
        repeat(200) {
            val nQuestions = 2 + rnd.nextInt(3)
            val nCands = 2 + rnd.nextInt(3)
            val questions = (0 until nQuestions).map { i ->
                q("q$i", *(0 until nCands).map { c -> name("M$c", rnd.nextGaussian()) }.toTypedArray(), t = rnd.nextGaussian() * 0.5)
            }
            val got = JointAssignment(DecoderSpec(DecoderKind.JOINT, sharing = SharingRules.NONE)).decode(questions, emptyList())
            assertThat(total(questions, got)).isWithin(1e-9).of(bruteForce(questions))
            // A candidate answers at most one question.
            assertThat(got.values.filterNotNull().distinct().size).isEqualTo(got.values.filterNotNull().size)
        }
    }

    @Test
    fun `calibrations keep the best candidate of a lone question`() {
        val questions = listOf(q("slot:total", amount("A1", 100, 0.2), amount("A2", 200, 0.9), amount("A3", 300, -0.1)))
        for (c in listOf(ScoreCalibration.RAW, ScoreCalibration.ZSCORE, ScoreCalibration.RANK, ScoreCalibration.SOFTMAX)) {
            val d = DecoderSpec(DecoderKind.JOINT, calibration = c, temperature = 0.5).create().decode(questions, emptyList())
            assertThat(d["slot:total"]).isEqualTo("A2")
        }
    }

    private fun total(questions: List<ScoredQuestion>, d: Map<String, String?>) =
        questions.sumOf { qq -> d[qq.name]?.let { id -> qq.candidates.first { it.id == id }.score } ?: qq.threshold }

    private fun bruteForce(questions: List<ScoredQuestion>): Double {
        var best = Double.NEGATIVE_INFINITY
        fun go(k: Int, used: Set<String>, sum: Double) {
            if (k == questions.size) { best = maxOf(best, sum); return }
            val qq = questions[k]
            go(k + 1, used, sum + qq.threshold)
            for (c in qq.candidates) if (c.id !in used) go(k + 1, used + c.id, sum + c.score)
        }
        go(0, emptySet(), 0.0)
        return best
    }
}
