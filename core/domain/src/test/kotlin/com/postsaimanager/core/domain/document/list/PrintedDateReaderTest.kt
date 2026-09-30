package com.postsaimanager.core.domain.document.list

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PrintedDateReaderTest {

    @Test
    fun `reads numeric, iso and long-form dates`() {
        assertThat(PrintedDateReader.read("15.10.2026")).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(PrintedDateReader.read("2026-10-15")).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(PrintedDateReader.read("15 October 2026")).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(PrintedDateReader.read("2026-10-15 09:30")).isEqualTo(LocalDate.of(2026, 10, 15))
    }

    @Test
    fun `a period in words, a time alone or nothing gives no date`() {
        assertThat(PrintedDateReader.read("within 14 days")).isNull()
        assertThat(PrintedDateReader.read("09:30")).isNull()
        assertThat(PrintedDateReader.read("")).isNull()
        assertThat(PrintedDateReader.read("31.02.2026")).isNull()
    }
}
