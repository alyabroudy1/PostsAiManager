package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.Validation
import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.CandidateSource
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** ML Kit's spans next to the shape candidates (a fake extractor's spans: the library itself runs on the phone). */
class EntityCandidateMergerTest {

    private val mini = MiniLetter()
    private val lines = mini.ocrText.lines().mapIndexed { i, t ->
        LayoutLine(t, 1, TextBounds(0.1f, 0.05f * i, 0.9f, 0.05f * i + 0.02f), 0.9f, LetterZone.BODY)
    }

    private fun merge(vararg spans: EntitySpan) = EntityCandidateMerger.merge(mini.set, spans.toList(), lines)

    private fun lineWith(text: String) = lines.indexOfFirst { it.text.contains(text) }

    @Test
    @DisplayName("a value the shape finders already hold adds nothing: the same date, amount, account, phone")
    fun `known values are not added twice`() {
        val merged = merge(
            EntitySpan(EntityType.DATE_TIME, lineWith("25.09.2026"), "25.09.2026", date = LocalDate.of(2026, 9, 25)),
            EntitySpan(EntityType.MONEY, lineWith("64,98"), "64,98 €", cents = 6498, currency = "EUR"),
            EntitySpan(EntityType.IBAN, lineWith("IBAN"), "DE02 1203 0000 0000 2020 51"),
            EntitySpan(EntityType.PHONE, lineWith("0800"), "0800 555 0199"),
        )

        assertThat(merged.added).isEqualTo(0)
        assertThat(merged.set.candidates).hasSize(mini.candidates.size)
    }

    @Test
    @DisplayName("a value only ML Kit found becomes a candidate of its own, with a K id, its line and its source")
    fun `new values are added`() {
        val merged = merge(
            EntitySpan(EntityType.DATE_TIME, lineWith("Gebühr"), "Heiligabend", date = LocalDate.of(2026, 12, 24)),
            EntitySpan(EntityType.DATE_TIME, lineWith("Gebühr"), "Ostern 10 Uhr", date = LocalDate.of(2027, 4, 4), time = LocalTime.of(10, 0)),
            EntitySpan(EntityType.MONEY, lineWith("Gebühr"), "12,50 €", cents = 1250, currency = "EUR"),
            EntitySpan(EntityType.EMAIL, lineWith("Gebühr"), "Service@Beispiel.example"),
            EntitySpan(EntityType.PHONE, lineWith("Gebühr"), "030 123456"),
            EntitySpan(EntityType.IBAN, lineWith("Gebühr"), "DE89 3704 0044 0532 0130 00"),
        )

        val added = merged.set.candidates.drop(mini.candidates.size)
        assertThat(added.map { it.id }).containsExactly("KD1", "KDT1", "KA1", "KE1", "KT1", "KI1").inOrder()
        assertThat(added.first { it.id == "KD1" }.normalized).isEqualTo("2026-12-24")
        assertThat(added.first { it.id == "KDT1" }.normalized).isEqualTo("2027-04-04T10:00")
        assertThat(added.first { it.id == "KA1" }.normalized).isEqualTo("12.50 EUR")
        assertThat(added.first { it.id == "KA1" }.cents).isEqualTo(1250L)
        assertThat(added.first { it.id == "KE1" }.normalized).isEqualTo("service@beispiel.example")
        assertThat(added.first { it.id == "KI1" }.validation).isEqualTo(Validation.Valid)
        assertThat(added.all { it.attrs[EntityCandidateMerger.SOURCE] == EntityCandidateMerger.SOURCE_MLKIT }).isTrue()
        assertThat(added.all { it.page == 1 && it.evidence.contains("Gebühr") }).isTrue()
        // The shape candidates keep their ids.
        assertThat(merged.set.candidates.take(mini.candidates.size)).isEqualTo(mini.candidates)
        assertThat(merged.set.letterDate).isEqualTo(mini.set.letterDate)
    }

    @Test
    @DisplayName("a time of day alone is not a date: ML Kit dates it to today, which the letter never said")
    fun `time only is skipped`() {
        val merged = merge(EntitySpan(EntityType.DATE_TIME, 0, "10:30 Uhr", date = LocalDate.of(2026, 10, 8), time = LocalTime.of(10, 30)))

        assertThat(merged.added).isEqualTo(0)
    }

    @Test
    @DisplayName("an amount with no cents, no currency or a span with no line is not a candidate")
    fun `incomplete spans`() {
        val merged = merge(
            EntitySpan(EntityType.MONEY, 0, "viel", cents = null, currency = "EUR"),
            EntitySpan(EntityType.MONEY, 0, "5", cents = 500, currency = null),
            EntitySpan(EntityType.EMAIL, 999, "a@b.example"),
        )

        assertThat(merged.added).isEqualTo(0)
    }

    @Test
    @DisplayName("an address is no candidate: the lines it covers are tagged for the reader instead")
    fun `address lines`() {
        val merged = merge(EntitySpan(EntityType.ADDRESS, 1, "Erika Mustermann"), EntitySpan(EntityType.ADDRESS, 2, "x"))

        assertThat(merged.added).isEqualTo(0)
        assertThat(merged.addressLines).containsExactly(1, 2)
    }

    @Test
    @DisplayName("the merged source gives the pipeline the shape candidates plus ML Kit's, and remembers what it merged")
    fun `merged source`() {
        val base = CandidateSource { _, _ -> mini.set }
        val source = MergedCandidateSource(base, listOf(EntitySpan(EntityType.EMAIL, 0, "a@b.example")))
        val layout = com.postsaimanager.core.domain.extraction.layout.LetterLayout(
            listOf(com.postsaimanager.core.domain.extraction.layout.PageLayoutView(1, lines)),
        )

        val set = source.find(emptyList(), layout)

        assertThat(set.ofKind(CandidateKind.EMAIL).map { it.id }).containsExactly("KE1")
        assertThat(source.lastMerged!!.added).isEqualTo(1)
    }
}
