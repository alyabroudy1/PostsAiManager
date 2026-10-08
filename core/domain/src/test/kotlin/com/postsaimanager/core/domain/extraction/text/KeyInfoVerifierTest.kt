package com.postsaimanager.core.domain.extraction.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class KeyInfoVerifierTest {

    private val ocr = """
        Stadtwerke Beispielstadt
        Zählernummer: 1EMH0012345678
        Vertragsbeginn: 01.01.2027
        Der Betrag von 1.284,50 € ist bis zum 15.10.2026 zu überweisen.
        Kundennummer KD-40417
        رقم العقد 55821
    """.trimIndent()

    private fun verify(vararg facts: Pair<String, String>, read: List<String> = emptyList()) =
        KeyInfoVerifier().verify(facts.map { KeyInfoFormat.Fact(it.first, it.second) }, ocr, read)

    @Test
    fun `a fact whose value is in the letter is kept with the label as the model wrote it`() {
        val kept = verify("Zählernummer des Hauses" to "1EMH0012345678", "Start" to "01.01.2027", "رقم العقد" to "55821")
        assertThat(kept.map { it.label to it.value })
            .containsExactly("Zählernummer des Hauses" to "1EMH0012345678", "Start" to "01.01.2027", "رقم العقد" to "55821").inOrder()
    }

    @Test
    fun `a value the letter never printed is dropped, also one that differs by a digit`() {
        assertThat(verify("Ort" to "Beispieldorf")).isEmpty()
        assertThat(verify("Kundennummer" to "KD-40418")).isEmpty()
        assertThat(verify("Betrag" to "1.284,59 €")).isEmpty()
        assertThat(verify("Frist" to "16.10.2026")).isEmpty()
        // The same value, spaced and cased differently, is the letter's.
        assertThat(verify("Kunde" to "kd 40417").map { it.value }).containsExactly("kd 40417")
    }

    @Test
    fun `a label that is a sentence fragment is dropped even when its value is in the letter`() {
        assertThat(verify("3. The letter indicates that the new 1.0" to "01.01.2027")).isEmpty()
        assertThat(verify("Der Vertrag beginnt am 01.01.2027" to "01.01.2027")).isEmpty()
        assertThat(verify("Eins zwei drei vier fünf" to "01.01.2027")).isEmpty()
        assertThat(verify("Beginn." to "01.01.2027")).isEmpty()
        assertThat(verify("Vertragsbeginn" to "01.01.2027")).hasSize(1)
    }

    @Test
    fun `an empty value or label is dropped`() {
        assertThat(verify("Leer" to "", "" to "1EMH0012345678", "  " to "01.01.2027")).isEmpty()
    }

    @Test
    fun `a value the reading already holds is dropped, amounts compared without their separators`() {
        val read = listOf("1.284,50 €", "KD-40417", "15.10.2026")
        assertThat(verify("Gesamt" to "1.284,50 €", "Kunde" to "KD-40417", "Frist" to "15.10.2026", read = read)).isEmpty()
        // Folded the same way: case, spacing and punctuation do not make a duplicate new.
        assertThat(verify("Kunde" to "kd 40417", read = read)).isEmpty()
        assertThat(verify("Zähler" to "1EMH0012345678", read = read)).hasSize(1)
    }

    @Test
    fun `an exact duplicate among the facts is kept once, the first label wins`() {
        val kept = verify("Zähler" to "1EMH0012345678", "Meter" to "1EMH0012345678")
        assertThat(kept.map { it.label }).containsExactly("Zähler")
    }

    @Test
    fun `the report names the reason of every drop and keeps a new label and value`() {
        val report = KeyInfoVerifier().report(
            listOf(
                KeyInfoFormat.Fact("Zählernummer", "1EMH0012345678"),
                KeyInfoFormat.Fact("Beginn", "01.01.2027"),
                KeyInfoFormat.Fact("Ort", "Beispieldorf"),
                KeyInfoFormat.Fact("Satz mit 3 Ziffern", "KD-40417"),
            ),
            ocr, listOf("01.01.2027"),
        )
        assertThat(report.kept.map { it.label }).containsExactly("Zählernummer")
        assertThat(report.dropped.map { it.label to it.reason }).containsExactly(
            "Beginn" to KeyInfoVerifier.DropReason.SAME_AS_READ_VALUE,
            "Ort" to KeyInfoVerifier.DropReason.NOT_IN_LETTER,
            "Satz mit 3 Ziffern" to KeyInfoVerifier.DropReason.LABEL_SHAPE,
        ).inOrder()
    }

    @Test
    fun `no more than the most facts are kept`() {
        val big = "x".repeat(1) + (1..20).joinToString("\n") { "Z$it" }
        val many = (1..20).map { KeyInfoFormat.Fact("Label ${'a' + it}", "Z$it") }
        val kept = KeyInfoVerifier().verify(many, big, emptyList())
        assertThat(kept).hasSize(KeyInfoFormat.MAX_FACTS)
    }
}
