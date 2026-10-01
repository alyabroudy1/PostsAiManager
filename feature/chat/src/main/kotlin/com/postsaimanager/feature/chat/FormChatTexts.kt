package com.postsaimanager.feature.chat

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.postsaimanager.core.model.CheckboxValue
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.FormValueSource
import java.util.IllegalFormatException

/**
 * Renders what the form conversation stored (codes and arguments, see [FormMessage]) from string resources, in the user's
 * language. The domain never writes UI text; a language switch re-renders every stored line.
 */
internal object FormChatTexts {

    /** The line a message says: what the model wrote (a question, the closing message) when there is something, otherwise the line for its code. */
    fun line(resources: Resources, form: FormMessage, content: String): String =
        content.ifBlank { form.text?.let { text(resources, it, form.args) }.orEmpty() }

    fun text(r: Resources, code: FormText, args: List<String>): String = when (code) {
        // The last step finished: the progress line becomes a plain "done" line instead of staying at "step 5 of 5".
        FormText.UNDERSTANDING ->
            if (args.size >= 2 && args[0] == args[1]) r.getString(R.string.form_understanding_done) else r.format(R.string.form_understanding, args)
        FormText.NO_MODEL -> r.getString(R.string.form_no_model)
        FormText.UNDERSTANDING_FAILED -> r.getString(R.string.form_understanding_failed)
        FormText.SEARCH_MODEL_MISSING -> r.getString(R.string.form_search_model_missing)
        FormText.BETA_NOTICE -> r.getString(R.string.form_beta_notice)
        FormText.AGENT_PAUSED -> r.getString(R.string.form_agent_paused)
        FormText.AGENT_STUCK -> r.getString(R.string.form_agent_stuck)
        FormText.AGENT_FAILED -> r.getString(R.string.form_agent_failed)
    }

    fun chipLabel(r: Resources, chip: FormChip): String = chip.label ?: when (chip.labelCode) {
        FormChipLabel.CONTINUE -> r.getString(R.string.form_chip_continue)
        FormChipLabel.DOWNLOAD -> r.getString(R.string.form_chip_download)
        null -> ""
    }

    /** A stored value as the user reads it: a tick box is Yes or No, anything else as it was written. */
    fun valueText(r: Resources, field: FormField): String = when (field.value) {
        CheckboxValue.YES -> r.getString(R.string.form_value_yes)
        CheckboxValue.NO -> r.getString(R.string.form_value_no)
        else -> field.value.orEmpty()
    }

    fun sourceBadge(r: Resources, source: FormValueSource): String? = when (source) {
        FormValueSource.PROFILE -> r.getString(R.string.form_source_profile)
        FormValueSource.FACT -> r.getString(R.string.form_source_fact)
        FormValueSource.USER -> r.getString(R.string.form_source_user)
        FormValueSource.TODAY -> r.getString(R.string.form_source_today)
        FormValueSource.FORM_OPTION -> r.getString(R.string.form_source_option)
        FormValueSource.NONE -> null
    }

    /** "8 of 11 ready · 2 need you · 1 signature". */
    fun progress(r: Resources, ready: Int, total: Int, needYou: Int, signatures: Int): String = buildString {
        append(r.getString(R.string.form_card_progress, ready, total))
        if (needYou > 0) append(" · ").append(r.getString(R.string.form_card_need_you, needYou))
        if (signatures > 0) append(" · ").append(r.getString(R.string.form_card_signature, signatures))
    }

    /** Formats a string resource with [args], padded with blanks so a stored message with fewer arguments never crashes the chat. */
    private fun Resources.format(@StringRes id: Int, args: List<String>): String = try {
        getString(id, *args.toTypedArray())
    } catch (_: IllegalFormatException) {
        getString(id, *Array(MAX_ARGS) { args.getOrElse(it) { "" } })
    }

    private const val MAX_ARGS = 4
}

/** The resources of the current composition. */
@Composable
internal fun rememberResources(): Resources = LocalContext.current.resources
