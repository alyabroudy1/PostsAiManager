package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner.Caps
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

/** A value the code had to repair (OCR read a digit as a letter) is offered, but its confidence is capped. */
class OcrRepairVerifierTest {

    @Test
    fun `a repaired IBAN is chosen normally and capped with the reason`() {
        val text = "IBAN: DEo2 10o1 0010 0006 8201 01"
        val prepared = Prepared(listOf(listOf(OcrBlock(text, TextBounds(0.1f, 0.5f, 0.9f, 0.52f), 0.9f))))
        val iban = prepared.find(CandidateKind.IBAN, "DE02100100100006820101")!!
        val json = """{"type":"bill","tc":"HIGH","lang":"de","parties":[],"s":{"iban":{"id":"${iban.id}","c":"HIGH"}},"x":[]}"""
        val raw = (InterpretationParser.parse(json) as InterpretationParser.Parsed.Ok).value
        val ctx = VerificationContext(prepared.candidates, prepared.offered, listOf(text), 0, 0, 1, 1)
        val v = SelectionVerifier().verify(raw, null, ctx).slots.getValue(Slots.IBAN)
        assertThat(v.normalized).isEqualTo("DE02100100100006820101")
        assertThat(v.value).isEqualTo("DEo2 10o1 0010 0006 8201 01")
        assertThat(v.confidence).isAtMost(Caps.REPAIRED)
        assertThat(v.blocked).isFalse()
        assertThat(v.notes.single()).contains("character repair")
    }
}
