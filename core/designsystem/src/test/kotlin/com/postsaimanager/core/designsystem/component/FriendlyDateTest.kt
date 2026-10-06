package com.postsaimanager.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.PersonRole
import com.postsaimanager.core.model.PersonTag
import org.junit.jupiter.api.Test
import java.time.LocalDate

class FriendlyDateTest {

    private val today = LocalDate.of(2026, 10, 6)

    @Test
    fun `today and tomorrow get their own reading`() {
        assertThat(FriendlyDate.of(today, today)).isEqualTo(FriendlyDate(FriendlyDate.Day.TODAY, showYear = false))
        assertThat(FriendlyDate.of(today.plusDays(1), today)).isEqualTo(FriendlyDate(FriendlyDate.Day.TOMORROW, showYear = false))
    }

    @Test
    fun `a date in this year is dated without the year`() {
        assertThat(FriendlyDate.of(LocalDate.of(2026, 11, 5), today)).isEqualTo(FriendlyDate(FriendlyDate.Day.LATER, showYear = false))
        assertThat(FriendlyDate.of(LocalDate.of(2026, 10, 3), today)).isEqualTo(FriendlyDate(FriendlyDate.Day.PAST, showYear = false))
    }

    @Test
    fun `a date in another year carries the year`() {
        assertThat(FriendlyDate.of(LocalDate.of(2027, 1, 15), today)).isEqualTo(FriendlyDate(FriendlyDate.Day.LATER, showYear = true))
        assertThat(FriendlyDate.of(LocalDate.of(2025, 12, 31), today)).isEqualTo(FriendlyDate(FriendlyDate.Day.PAST, showYear = true))
    }

    @Test
    fun `tomorrow across new year still reads tomorrow, with the year`() {
        val newYearsEve = LocalDate.of(2026, 12, 31)
        assertThat(FriendlyDate.of(LocalDate.of(2027, 1, 1), newYearsEve)).isEqualTo(FriendlyDate(FriendlyDate.Day.TOMORROW, showYear = true))
    }

    @Test
    fun `at most two person chips are shown and the rest is counted`() {
        fun person(n: Int) = PersonTag("p$n", "P$n", PersonRole.FOR, isMe = false)
        assertThat(PersonChipSplit.of(emptyList())).isEqualTo(PersonChipSplit(emptyList(), 0))
        assertThat(PersonChipSplit.of(listOf(person(1), person(2)))).isEqualTo(PersonChipSplit(listOf(person(1), person(2)), 0))
        assertThat(PersonChipSplit.of((1..5).map(::person))).isEqualTo(PersonChipSplit(listOf(person(1), person(2)), 3))
    }
}
