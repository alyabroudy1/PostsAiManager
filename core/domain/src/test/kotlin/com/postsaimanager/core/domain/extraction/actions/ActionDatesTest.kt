package com.postsaimanager.core.domain.extraction.actions

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class ActionDatesTest {

    @Test
    fun `a date is read as printed in any of the shapes the finder knows`() {
        assertThat(ActionDates.read("15.10.2026")).isEqualTo(ActionDate(LocalDate.of(2026, 10, 15)))
        assertThat(ActionDates.read("2026-10-15")).isEqualTo(ActionDate(LocalDate.of(2026, 10, 15)))
        assertThat(ActionDates.read("10 October 2026")).isEqualTo(ActionDate(LocalDate.of(2026, 10, 10)))
    }

    @Test
    fun `a date with a time of day keeps the time`() {
        assertThat(ActionDates.read("12.11.2026 09:30")).isEqualTo(ActionDate(LocalDate.of(2026, 11, 12), LocalTime.of(9, 30)))
    }

    @Test
    fun `words that hold no date read as none`() {
        assertThat(ActionDates.read("innerhalb von 14 Tagen nach Zugang")).isNull()
        assertThat(ActionDates.read("")).isNull()
    }
}
