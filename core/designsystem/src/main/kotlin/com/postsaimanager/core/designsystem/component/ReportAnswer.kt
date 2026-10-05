package com.postsaimanager.core.designsystem.component

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.R

/**
 * "Report this answer" (Google Play's generative-AI policy): builds an e-mail draft the user reviews and sends
 * themselves. Nothing is ever sent by the app, and the AI answer text is part of the draft only when the user
 * ticked the option in [ReportAnswerDialog].
 */
object ReportAnswer {
    const val CONTACT_EMAIL = "alyabroudy1@gmail.com"
    const val SUBJECT = "PostsAiManager: AI answer report"

    /**
     * The draft body: [intro] (what was wrong?), then, only when [includeAnswer] is true and there is a text, the
     * [answerHeading] and the [answerText]. The default call site passes `includeAnswer = false`.
     */
    fun body(intro: String, answerHeading: String, answerText: String?, includeAnswer: Boolean): String =
        if (includeAnswer && !answerText.isNullOrBlank()) {
            "$intro\n\n---\n$answerHeading\n$answerText\n"
        } else {
            intro
        }

    /** The `mailto:` draft: `ACTION_SENDTO`, so only e-mail apps answer it. */
    fun intent(body: String): Intent =
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$CONTACT_EMAIL")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(CONTACT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, SUBJECT)
            putExtra(Intent.EXTRA_TEXT, body)
        }
}

/** The small flag button shown on an AI answer. */
@Composable
fun ReportAnswerButton(onClick: () -> Unit, modifier: Modifier = Modifier, iconSize: Int = 16) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Outlined.Flag,
            contentDescription = stringResource(R.string.report_answer_action),
            modifier = Modifier.size(iconSize.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Confirms the report and explains what happens: an e-mail draft opens, nothing is sent until the user sends it.
 * "Include the answer text" is off by default.
 */
@Composable
fun ReportAnswerDialog(answerText: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var includeAnswer by rememberSaveable { mutableStateOf(false) }
    val intro = stringResource(R.string.report_answer_mail_intro)
    val heading = stringResource(R.string.report_answer_mail_answer_heading)
    val noMailApp = stringResource(R.string.report_answer_no_mail_app)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_answer_title)) },
        text = {
            Column {
                Text(stringResource(R.string.report_answer_message), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox) { includeAnswer = !includeAnswer },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeAnswer, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.report_answer_include), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                launchDraft(context, ReportAnswer.body(intro, heading, answerText, includeAnswer), noMailApp)
                onDismiss()
            }) { Text(stringResource(R.string.report_answer_write_email)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.report_answer_cancel)) }
        },
    )
}

private fun launchDraft(context: Context, body: String, noMailApp: String) {
    try {
        context.startActivity(ReportAnswer.intent(body))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, noMailApp, Toast.LENGTH_LONG).show()
    }
}
