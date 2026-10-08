package com.postsaimanager.core.domain.extraction.text

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.GbnfMatcher
import org.junit.jupiter.api.Test

class KeyInfoFormatTest {

    private val matcher = GbnfMatcher(KeyInfoFormat.grammar())

    private fun fact(label: String = "Zählernummer", value: String = "1EMH0012345678") = "$label: $value"

    @Test
    fun `the grammar accepts NONE, one fact and a full list of facts in any script`() {
        assertThat(matcher.accepts("NONE")).isTrue()
        assertThat(matcher.accepts(fact())).isTrue()
        assertThat(matcher.accepts(fact("المرجع", "REF-5521"))).isTrue()
        val full = (1..KeyInfoFormat.MAX_FACTS).joinToString("\n") { fact("Label ${'a' + it}", "Wert $it") }
        assertThat(matcher.accepts(full)).isTrue()
    }

    @Test
    fun `the grammar forces the label colon value shape`() {
        assertThat(matcher.accepts("")).isFalse()
        assertThat(matcher.accepts("Zählernummer 1EMH0012345678")).isFalse()
        assertThat(matcher.accepts("Zählernummer:1EMH0012345678")).isFalse()
        assertThat(matcher.accepts(": ohne Label")).isFalse()
        assertThat(matcher.accepts("Label: ")).isFalse()
        // A label has no colon (the first one ends it, a value may hold more), a fact is one line, and a line holds exactly one fact.
        assertThat(matcher.accepts("Ort: Zeit: Wert")).isTrue()
        assertThat(matcher.accepts("Ort : Wert")).isTrue()
        assertThat(matcher.accepts("Label\n: Wert")).isFalse()
        assertThat(matcher.accepts("Label: Wert\n\nLabel: Wert")).isFalse()
        assertThat(matcher.accepts("NONE\n" + fact())).isFalse()
    }

    @Test
    fun `the grammar bounds the number of facts and the length of a label and of a value`() {
        val tooMany = (1..KeyInfoFormat.MAX_FACTS + 1).joinToString("\n") { fact("Label ${'a' + it}", "Wert $it") }
        assertThat(matcher.accepts(tooMany)).isFalse()
        assertThat(matcher.accepts(fact("L".repeat(KeyInfoFormat.MAX_LABEL_CHARS), "v"))).isTrue()
        assertThat(matcher.accepts(fact("L".repeat(KeyInfoFormat.MAX_LABEL_CHARS + 1), "v"))).isFalse()
        assertThat(matcher.accepts(fact("L", "v".repeat(KeyInfoFormat.MAX_VALUE_CHARS)))).isTrue()
        assertThat(matcher.accepts(fact("L", "v".repeat(KeyInfoFormat.MAX_VALUE_CHARS + 1)))).isFalse()
    }

    @Test
    fun `the grammar refuses a label with a digit or sentence punctuation`() {
        assertThat(matcher.accepts(fact("Zählernummer", "1"))).isTrue()
        assertThat(matcher.accepts(fact("3. Das Schreiben", "1"))).isFalse()
        assertThat(matcher.accepts(fact("Gilt ab 1.0", "1"))).isFalse()
        assertThat(matcher.accepts(fact("Was ist das?", "1"))).isFalse()
        assertThat(matcher.accepts(fact("Hinweis!", "1"))).isFalse()
    }

    @Test
    fun `a label is one to four words of at most 30 characters, without digits or sentence punctuation`() {
        assertThat(KeyInfoFormat.isLabelShape("Zählernummer")).isTrue()
        assertThat(KeyInfoFormat.isLabelShape("Vertrag läuft bis")).isTrue()
        assertThat(KeyInfoFormat.isLabelShape("رقم العقد")).isTrue()
        assertThat(KeyInfoFormat.isLabelShape("eins zwei drei vier fünf")).isFalse()
        assertThat(KeyInfoFormat.isLabelShape("3. The letter indicates that the new 1.0")).isFalse()
        assertThat(KeyInfoFormat.isLabelShape("Der Brief besagt etwas")).isTrue()
        assertThat(KeyInfoFormat.isLabelShape("Betrag 2027")).isFalse()
        assertThat(KeyInfoFormat.isLabelShape("رقم ٢٠٢٧")).isFalse()
        assertThat(KeyInfoFormat.isLabelShape("Hinweis.")).isFalse()
        assertThat(KeyInfoFormat.isLabelShape("Warum?")).isFalse()
        assertThat(KeyInfoFormat.isLabelShape("L".repeat(31))).isFalse()
        assertThat(KeyInfoFormat.isLabelShape("  ")).isFalse()
    }

    @Test
    fun `the answer is parsed into facts, NONE and lines without a colon give none`() {
        assertThat(KeyInfoFormat.parse("NONE")).isEmpty()
        assertThat(KeyInfoFormat.parse("  ")).isEmpty()
        assertThat(KeyInfoFormat.parse("kein Doppelpunkt")).isEmpty()
        assertThat(KeyInfoFormat.parse("Frist: 15.10.2026: spätestens\nTarif: Basis 12 "))
            .containsExactly(KeyInfoFormat.Fact("Frist", "15.10.2026: spätestens"), KeyInfoFormat.Fact("Tarif", "Basis 12")).inOrder()
        val many = (1..KeyInfoFormat.MAX_FACTS + 3).joinToString("\n") { "L$it: v$it" }
        assertThat(KeyInfoFormat.parse(many)).hasSize(KeyInfoFormat.MAX_FACTS)
    }
}
