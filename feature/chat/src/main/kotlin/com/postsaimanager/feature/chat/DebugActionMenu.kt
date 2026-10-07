package com.postsaimanager.feature.chat

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.postsaimanager.core.domain.skills.AgentAction
import java.time.LocalDateTime

/**
 * Sample actions for trying the action cards before the model proposes any (phase 2b connects it). Debug builds only: the values
 * are invented on purpose, so the grounding flags show on the card.
 */
internal object DebugActionSamples {

    fun email(): AgentAction = AgentAction.SendEmail(
        to = "sender@example.com",
        subject = "Re: reference 4711-0815",
        body = "Dear Sir or Madam,\n\nthank you for your letter. I will pay the amount of 123,45 EUR by the due date.\n\nKind regards",
    )

    fun event(now: LocalDateTime = LocalDateTime.now()): AgentAction {
        val start = now.plusDays(SAMPLE_DAYS_AHEAD).withHour(SAMPLE_HOUR).withMinute(0).withSecond(0).withNano(0)
        return AgentAction.CreateCalendarEvent(title = "Deadline: sample letter", start = start, end = start.plusHours(1), description = "Reference 4711-0815")
    }

    fun reminder(now: LocalDateTime = LocalDateTime.now()): AgentAction =
        AgentAction.ScheduleReminder(
            at = now.plusDays(SAMPLE_DAYS_AHEAD).withHour(SAMPLE_HOUR).withMinute(0).withSecond(0).withNano(0),
            text = "Pay 123,45 EUR, reference 4711-0815",
            documentId = null,
        )

    private const val SAMPLE_DAYS_AHEAD = 2L
    private const val SAMPLE_HOUR = 9
}

/** True in a debuggable build, false in the release build; the debug entry points show only there. */
@Composable
internal fun rememberIsDebugBuild(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
}

/** A three-dot menu with "Test agent action" items; draws nothing in a release build. */
@Composable
internal fun DebugActionMenu(onPropose: (AgentAction) -> Unit) {
    if (!rememberIsDebugBuild()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("debugMenu")) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.debug_menu_description))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.debug_test_email_action)) },
                onClick = { open = false; onPropose(DebugActionSamples.email()) },
                modifier = Modifier.testTag("debugTestEmail"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.debug_test_event_action)) },
                onClick = { open = false; onPropose(DebugActionSamples.event()) },
                modifier = Modifier.testTag("debugTestEvent"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.debug_test_reminder_action)) },
                onClick = { open = false; onPropose(DebugActionSamples.reminder()) },
                modifier = Modifier.testTag("debugTestReminder"),
            )
        }
    }
}
