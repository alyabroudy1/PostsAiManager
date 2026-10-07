package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.address.LineAsk
import com.postsaimanager.core.domain.extraction.v2.AskRecord
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FamilyClassifierTest {

    private val schema = ExtractionSchema.DEFAULT
    private val profile = ScoringProfile(thresholds = mapOf(ScoringProfile.FAMILY to 0.0, ScoringProfile.TOPICS to 0.0))

    /** A session whose scores come from [scores]: a family or topic id to its log-odds; everything else scores [other]. */
    private suspend fun session(scores: Map<String, Double>, other: Double = -2.0): FakePromptSession {
        val byText = HashMap<String, Double>()
        for (f in schema.families) byText["Is this document ${f.description}? Answer:"] = scores[f.id] ?: other
        for (t in schema.topics) byText["Does this document concern ${t.description}? Answer:"] = scores[t.id] ?: other
        return FakePromptSession().apply {
            open("prefix")
            scorer = { continuation -> byText.entries.first { continuation.contains(it.key) }.value }
        }
    }

    @Test
    fun `the best family above the threshold wins and its margin gives the confidence`() = runTest {
        val session = session(mapOf("invoice_bill" to 1.5, "official_letter" to 0.2))
        val c = FamilyClassifier(session, profile).classify(DocDirection.INCOMING)!!
        assertThat(c.family.id).isEqualTo("invoice_bill")
        assertThat(c.familyConfidence).isEqualTo(profile.confidence(1.3))
    }

    @Test
    fun `no family above the threshold is the abstain family`() = runTest {
        val session = session(mapOf("invoice_bill" to -0.1, "official_letter" to -0.4))
        val c = FamilyClassifier(session, profile).classify(DocDirection.INCOMING)!!
        assertThat(c.family.id).isEqualTo("free_form")
        assertThat(c.familyConfidence).isEqualTo("LOW")
    }

    @Test
    fun `a score exactly at the threshold abstains`() = runTest {
        val session = session(mapOf("receipt" to 0.0))
        assertThat(FamilyClassifier(session, profile).classify(DocDirection.INCOMING)!!.family.id).isEqualTo("free_form")
    }

    @Test
    fun `free_form is never asked about`() = runTest {
        val session = session(emptyMap())
        FamilyClassifier(session, profile).classify(DocDirection.INCOMING)
        val asked = session.scored.single()
        val freeForm = ExtractionSchema.FREE_FORM.description
        assertThat(asked.none { it.contains("Is this document $freeForm?") }).isTrue()
        assertThat(asked.none { it.contains("free_form") }).isTrue()
    }

    @Test
    fun `topics are multi-label, every topic above the threshold and the best first`() = runTest {
        val session = session(mapOf("invoice_bill" to 1.0, "insurance" to 0.4, "health" to 1.2, "tax" to -0.5, "telecom" to 0.1))
        val c = FamilyClassifier(session, profile).classify(DocDirection.INCOMING)!!
        assertThat(c.topics).containsExactly("health", "insurance", "telecom").inOrder()
    }

    @Test
    fun `one batch of 11 families and 14 topics, named score family`() = runTest {
        val session = session(mapOf("receipt" to 1.0))
        val records = ArrayList<AskRecord>()
        val c = FamilyClassifier(session, profile, onRecord = { records += it }).classify(DocDirection.INCOMING)!!
        assertThat(session.scored).hasSize(1)
        assertThat(session.scored.single().size).isEqualTo(25)
        assertThat(c.scores).hasSize(25)
        assertThat(records.single().name).isEqualTo("score:family")
        assertThat(records.single().answer!!.split(',')).hasSize(25)
    }

    @Test
    fun `a short appointment reminder can be the appointment family instead of an official letter`() = runTest {
        val session = session(mapOf("appointment_reminder" to 1.4, "official_letter" to 0.6))
        val c = FamilyClassifier(session, profile).classify(DocDirection.INCOMING)!!
        assertThat(c.family.id).isEqualTo("appointment_reminder")
    }

    @Test
    fun `a lead under the minimum margin is the neutral abstain family, not a forced official letter`() = runTest {
        val margined = profile.copy(familyMinMargin = 0.3)
        val close = session(mapOf("official_letter" to 0.5, "message_note" to 0.4))
        val abstained = FamilyClassifier(close, margined).classify(DocDirection.INCOMING)!!
        assertThat(abstained.family.id).isEqualTo("free_form")
        assertThat(abstained.familyConfidence).isEqualTo("LOW")
        assertThat(abstained.scores["family:official_letter"]).isEqualTo(0.5)
        val clear = session(mapOf("official_letter" to 0.9, "message_note" to 0.4))
        assertThat(FamilyClassifier(clear, margined).classify(DocDirection.INCOMING)!!.family.id).isEqualTo("official_letter")
    }

    @Test
    fun `the winner of the scores needs the threshold and the lead, and a lone score leads by itself`() {
        val margined = ScoringProfile(thresholds = mapOf(ScoringProfile.FAMILY to 0.0), familyMinMargin = 0.2)
        assertThat(margined.familyWinner(listOf(0.1, 0.9, 0.5))).isEqualTo(1)
        assertThat(margined.familyWinner(listOf(0.1, 0.9, 0.8))).isNull()
        assertThat(margined.familyWinner(listOf(-0.5, -0.1))).isNull()
        assertThat(margined.familyWinner(listOf(0.3))).isEqualTo(0)
        assertThat(margined.familyWinner(emptyList())).isNull()
        assertThat(ScoringProfile().familyWinner(listOf(0.5, 0.5))).isEqualTo(0)
    }

    @Test
    fun `the questions carry the closing text of the turn`() = runTest {
        val session = session(emptyMap())
        FamilyClassifier(session, profile).classify(DocDirection.INCOMING, tail = "<END>")
        assertThat(session.scored.single().all { it.endsWith("<END>") }).isTrue()
    }

    @Test
    fun `a forced family skips the family scores and still finds the topics`() = runTest {
        val session = session(mapOf("official_letter" to 5.0, "tax" to 0.7, "government" to -1.0))
        val c = FamilyClassifier(session, profile).classify(ExtractionSchema.MEDICAL)!!
        assertThat(c.family.id).isEqualTo("medical")
        assertThat(c.familyConfidence).isEqualTo("HIGH")
        assertThat(c.topics).containsExactly("tax")
        assertThat(session.scored.single()).hasSize(schema.topics.size)
        assertThat(c.scores.keys.none { it.startsWith("family:") }).isTrue()
    }

    @Test
    fun `with the topics moved to the second stage only the family is scored there`() = runTest {
        val session = session(mapOf("receipt" to 1.0, "shopping" to 2.0))
        val c = FamilyClassifier(session, profile).classify(DocDirection.INCOMING, includeTopics = false)!!
        assertThat(c.family.id).isEqualTo("receipt")
        assertThat(c.topics).isEmpty()
        assertThat(session.scored.single()).hasSize(11)
        val later = FamilyClassifier(session, profile).topics()!!
        assertThat(later).containsExactly("shopping")
    }

    @Test
    fun `an outgoing letter is classified among its own direction's families`() = runTest {
        val session = session(mapOf("outgoing_letter" to 1.0))
        val c = FamilyClassifier(session, profile).classify(DocDirection.OUTGOING)!!
        assertThat(c.family.id).isEqualTo("outgoing_letter")
    }

    @Test
    fun `a failed batch is null, and is still recorded`() = runTest {
        val session = FakePromptSession()  // no prefix open: the score call fails
        val records = ArrayList<AskRecord>()
        assertThat(FamilyClassifier(session, profile, onRecord = { records += it }).classify(DocDirection.INCOMING)).isNull()
        assertThat(records.single().answer).isNull()
    }

    @Test
    fun `the qwen profile has the thresholds and the stage flag`() {
        val qwen = ModelProfiles.QWEN35_08B
        assertThat(qwen.topicsInFirstStage).isFalse()
        assertThat(qwen.scoring.thresholds).containsKey(ScoringProfile.FAMILY)
        assertThat(qwen.scoring.thresholds).containsKey(ScoringProfile.TOPICS)
        assertThat(qwen.scoring.thresholds).containsKey(LineAsk.LABEL_ASK)
        assertThat(qwen.scoring.thresholds).containsKey(LineAsk.DELIVERY_ASK)
        assertThat(qwen.scoring.familyThreshold).isGreaterThan(qwen.scoring.defaultThreshold)
    }
}
