package com.postsaimanager.core.domain.timeline

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

class EventDateResolverTest {

    private val zone = ZoneOffset.UTC
    private val kinds = EventKinds.DEFAULT
    private val scanned = LocalDate.of(2026, 10, 7).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private var next = 0
    private fun date(slot: String, value: String, meaning: String? = null, deleted: Boolean = false) = ExtractedData(
        id = "f${next++}", documentId = "d1", fieldName = slot, fieldValue = value, fieldType = ExtractedFieldType.DATE, confidence = 0.9f,
        slotKey = slot, role = meaning?.let { "meaning:$it" }, deletedByUser = deleted,
    )

    private fun resolve(kind: String, vararg fields: ExtractedData) = EventDateResolver.resolve(kinds.byId(kind), fields.toList(), scanned, zone)

    private val letter = date("letter_date", "02.09.2026", "LETTER_DATE")
    private val appointment = date("event_date", "20.10.2026", "APPOINTMENT")
    private val validFrom = date("period_start", "01.09.2026", "PERIOD_START")
    private val due = date("due_date", "30.09.2026", "DUE_DATE")

    @Test
    fun `an appointment is on the appointment date`() {
        assertThat(resolve(EventKinds.APPOINTMENT, letter, appointment, due)).isEqualTo(day(2026, 10, 20))
    }

    @Test
    fun `a payment demand is on the letter date, not the due date`() {
        assertThat(resolve(EventKinds.PAYMENT_DEMAND, letter, due)).isEqualTo(day(2026, 9, 2))
    }

    @Test
    fun `a decision is on the valid-from date when the letter has one, else the letter date`() {
        assertThat(resolve(EventKinds.APPROVAL, letter, validFrom)).isEqualTo(day(2026, 9, 1))
        assertThat(resolve(EventKinds.APPROVAL, letter)).isEqualTo(day(2026, 9, 2))
        assertThat(resolve(EventKinds.REJECTION, letter, validFrom)).isEqualTo(day(2026, 9, 2))
    }

    @Test
    fun `a kind whose meaning is not stored falls back to the letter date`() {
        assertThat(resolve(EventKinds.APPOINTMENT, letter)).isEqualTo(day(2026, 9, 2))
    }

    @Test
    fun `the letter date is also read from the slot the schema calls the document date`() {
        assertThat(resolve(EventKinds.INFORMATION, date("letter_date", "15.08.2026"))).isEqualTo(day(2026, 8, 15))
    }

    @Test
    fun `no date at all is the day the document was added`() {
        assertThat(resolve(EventKinds.INFORMATION)).isEqualTo(day(2026, 10, 7))
    }

    @Test
    fun `a date the user deleted is ignored`() {
        assertThat(resolve(EventKinds.APPOINTMENT, letter, date("event_date", "20.10.2026", "APPOINTMENT", deleted = true))).isEqualTo(day(2026, 9, 2))
    }

    @Test
    fun `a re-read with no date of the letter keeps the date the event had, not the scan day`() {
        val earlier = day(2026, 9, 1)

        assertThat(EventDateResolver.resolve(kinds.byId(EventKinds.APPLICATION_FILED), emptyList(), scanned, zone, earlierEventDate = earlier)).isEqualTo(earlier)
    }

    @Test
    fun `a date of the letter beats the earlier event date, whatever its meaning or slot`() {
        val earlier = day(2026, 9, 1)
        val fields = arrayOf(date("letter_date", "05.09.2026"))

        assertThat(EventDateResolver.resolve(kinds.byId(EventKinds.INFORMATION), fields.toList(), scanned, zone, earlierEventDate = earlier)).isEqualTo(day(2026, 9, 5))
        assertThat(EventDateResolver.resolve(kinds.byId(EventKinds.INFORMATION), listOf(date("event_date", "07.09.2026", "LETTER_DATE")), scanned, zone, earlierEventDate = earlier))
            .isEqualTo(day(2026, 9, 7))
    }

    @Test
    fun `every date meaning a kind names exists in the value meanings`() {
        val known = com.postsaimanager.core.domain.extraction.v2.ValueMeanings.DEFAULT.all.map { it.id }.toSet()
        for (kind in kinds.all) assertThat(known).containsAtLeastElementsIn(kind.dateMeanings)
    }
}
