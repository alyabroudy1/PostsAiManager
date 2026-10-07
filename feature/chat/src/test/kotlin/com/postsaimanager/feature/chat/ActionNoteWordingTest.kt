package com.postsaimanager.feature.chat

import android.content.Context
import android.content.res.Configuration
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.memory.ForgetActionNoteUseCase
import com.postsaimanager.core.domain.memory.RecordActionNoteUseCase
import com.postsaimanager.core.domain.skills.ActionCardStatus
import com.postsaimanager.core.domain.skills.AgentAction
import com.postsaimanager.core.testing.FakeDocumentNoteRepository
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year
import java.util.Locale

/**
 * The words of an action's note over the real string resources (Robolectric): the note is stored in the language of the moment, with
 * the date as a month name in the user's own order (never digits alone) and the locale's short time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionNoteWordingTest {

    private val year = Year.now().value
    private val at = LocalDateTime.of(year, 10, 8, 9, 0)

    private fun wording(locale: Locale): AndroidActionNoteWording {
        val base = RuntimeEnvironment.getApplication() as Context
        val config = Configuration(base.resources.configuration).apply { setLocales(android.os.LocaleList(locale)) }
        return AndroidActionNoteWording(base.createConfigurationContext(config))
    }

    @Test
    fun `a reminder note reads with the date, the time and the reminder's text`() {
        assertThat(wording(Locale.UK).reminder(at, "Send the documents for the Bürgergeld application"))
            .isEqualTo("Reminder set for 8 Oct 09:00: Send the documents for the Bürgergeld application")
    }

    @Test
    fun `an email note says when it was opened, and omits an empty subject`() {
        val opened = LocalDate.of(year, 10, 7)

        assertThat(wording(Locale.UK).email("jobcenter@example.de", opened, "Documents")).isEqualTo("Email to jobcenter@example.de opened on 7 Oct: Documents")
        assertThat(wording(Locale.UK).email("jobcenter@example.de", opened, " ")).isEqualTo("Email to jobcenter@example.de opened on 7 Oct")
    }

    @Test
    fun `a calendar note reads with the event's start and title`() {
        assertThat(wording(Locale.UK).calendarEvent(at, "Appointment")).isEqualTo("Calendar event added for 8 Oct 09:00: Appointment")
    }

    @Test
    fun `German and Arabic notes use their own words and month names`() {
        val german = wording(Locale.GERMANY).reminder(at, "Unterlagen schicken")
        assertThat(german).startsWith("Erinnerung gestellt für 8. Okt.")
        assertThat(german).endsWith(": Unterlagen schicken")

        val arabic = wording(Locale("ar")).reminder(at, "x")
        assertThat(arabic).startsWith("تم ضبط تذكير في")
        assertThat(arabic).doesNotContain("%")
    }

    @Test
    fun `only an opened card has a note, in the user's words`() {
        val repo = FakeDocumentNoteRepository()
        val notes = ActionNotes(RecordActionNoteUseCase(repo), ForgetActionNoteUseCase(repo), wording(Locale.UK))
        val reminder = AgentAction.ScheduleReminder(at, "Pay", "d1")

        kotlinx.coroutines.runBlocking {
            notes.sync("d1", "c1", reminder, ActionCardStatus.PENDING, at)
            assertThat(repo.snapshot).isEmpty()
            notes.sync("d1", "c1", reminder, ActionCardStatus.OPENED, at)
            assertThat(repo.snapshot.map { it.text }).containsExactly("Reminder set for 8 Oct 09:00: Pay")
            notes.sync("d1", "c1", reminder, ActionCardStatus.CANCELLED, at)
            assertThat(repo.snapshot).isEmpty()
        }
    }

    @Test
    fun `reading the clock is no note`() {
        assertThat(ActionNotes(RecordActionNoteUseCase(FakeDocumentNoteRepository()), ForgetActionNoteUseCase(FakeDocumentNoteRepository()), wording(Locale.UK)).textOf(AgentAction.GetDateTime, at)).isNull()
    }
}
