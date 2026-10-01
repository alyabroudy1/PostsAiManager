package com.postsaimanager.feature.chat

import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.postsaimanager.core.model.CheckboxValue
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.FormValueSource
import java.util.IllegalFormatException
import java.util.Locale

/**
 * Renders what the form conversation stored (codes and arguments, see [FormMessage]) from string resources, in the user's
 * language. The domain never writes UI text; a language switch re-renders every stored line.
 */
internal object FormChatTexts {

    /** The line a message says: the question the model wrote when there is one, otherwise the template for its code. */
    fun line(resources: Resources, form: FormMessage, content: String): String {
        // An earlier build stored the model's reasoning as the question: it is never shown (the template line is).
        val body = if (content.isNotBlank() && !content.contains("<think", ignoreCase = true)) {
            content
        } else {
            form.text?.let { text(inLanguage(resources, form.localeTag), it, form.args) }.orEmpty()
        }
        val context = questionContext(form)
        return if (context == null || body.isBlank()) body else "$context\n$body"
    }

    /**
     * Where the question's field is on the form ("Section · p.2"): the same in front of the model's wording and of the template, so
     * a generic question still says which field is meant. A question carries it as its label, section and page arguments.
     */
    fun questionContext(form: FormMessage): String? {
        if (form.kind != FormMessageKind.QUESTION || form.fieldId == null || form.args.size < 3) return null
        val section = form.args[1].trim()
        val page = form.args[2].trim().takeIf { it.isNotEmpty() }?.let { "p.$it" }
        return listOfNotNull(section.takeIf { it.isNotEmpty() }, page).joinToString(" · ").ifEmpty { null }
    }

    /**
     * [resources] in the language of [localeTag] (a template question follows the form's language); the same resources when the tag
     * is absent or already the current language. A language with no strings falls back to the English defaults, as always.
     */
    @Suppress("DEPRECATION")
    fun inLanguage(resources: Resources, localeTag: String?): Resources {
        if (localeTag.isNullOrBlank()) return resources
        val locale = Locale.forLanguageTag(localeTag)
        if (resources.configuration.locales[0].language == locale.language) return resources
        val configuration = Configuration(resources.configuration).apply { setLocale(locale) }
        return Resources(resources.assets, resources.displayMetrics, configuration)
    }

    fun text(r: Resources, code: FormText, args: List<String>): String = when (code) {
        // The last step finished: the progress line becomes a plain "done" line instead of staying at "step 5 of 5".
        FormText.UNDERSTANDING ->
            if (args.size >= 2 && args[0] == args[1]) r.getString(R.string.form_understanding_done) else r.format(R.string.form_understanding, args)
        FormText.NO_MODEL -> r.getString(R.string.form_no_model)
        FormText.UNDERSTANDING_FAILED -> r.getString(R.string.form_understanding_failed)
        FormText.FORM_FOUND_ASK_SUBJECT -> r.format(R.string.form_found_ask_subject, listOf(counts(r, args)))
        FormText.FORM_FOUND_ASK_SUBJECT_REASON ->
            r.format(R.string.form_found_ask_subject_reason, listOf(counts(r, args), args.getOrElse(3) { "" }, args.getOrElse(2) { "" }))
        FormText.ASK_SUBJECT -> r.getString(R.string.form_ask_subject)
        FormText.ASK_GUARDIAN -> r.getString(R.string.form_ask_guardian)
        FormText.ASK_PAYER -> r.getString(R.string.form_ask_payer)
        FormText.FILLED_INTRO -> r.format(R.string.form_filled_intro, args)
        FormText.ASK_TEXT -> r.format(R.string.form_ask_text, args)
        FormText.ASK_DATE -> r.format(R.string.form_ask_date, args)
        FormText.ASK_CHOICE -> r.format(R.string.form_ask_choice, args)
        FormText.ASK_YES_NO -> r.format(R.string.form_ask_yes_no, args)
        FormText.STILL_RIGHT -> r.format(R.string.form_still_right, args)
        FormText.HINT_NOT_AN_OPTION -> r.format(R.string.form_hint_not_an_option, args)
        FormText.HINT_NOT_A_DATE -> r.getString(R.string.form_hint_not_a_date)
        FormText.HINT_NOT_A_PHONE -> r.getString(R.string.form_hint_not_a_phone)
        FormText.HINT_NOT_AN_EMAIL -> r.getString(R.string.form_hint_not_an_email)
        FormText.HINT_NOT_AN_IBAN -> r.getString(R.string.form_hint_not_an_iban)
        FormText.HINT_NOT_A_POSTCODE -> r.getString(R.string.form_hint_not_a_postcode)
        FormText.HINT_EMPTY -> r.format(R.string.form_hint_empty, args)
        FormText.LEFT_FOR_YOU -> r.format(R.string.form_left_for_you, args)
        FormText.REMEMBER -> r.format(R.string.form_remember, args)
        FormText.REMEMBERED -> r.format(R.string.form_remembered, args)
        FormText.REMEMBER_FAILED -> r.getString(R.string.form_remember_failed)
        FormText.MORE_QUESTIONS -> plural(r, R.plurals.form_more_questions, args.firstOrNull())
        FormText.BY_HAND -> plural(r, R.plurals.form_by_hand, args.firstOrNull())
        FormText.ALL_SET -> allSet(r, args)
        FormText.STOPPED -> r.getString(R.string.form_stopped)
        FormText.SUBJECT_CHANGED -> r.getString(R.string.form_subject_changed)
        FormText.ASK_ROLE_NAME -> r.getString(R.string.form_ask_role_name)
        FormText.ME_SETUP_HINT -> r.getString(R.string.form_me_setup_hint)
        FormText.READING_PAUSED -> r.getString(R.string.form_reading_paused)
        FormText.SEARCH_MODEL_MISSING -> r.getString(R.string.form_search_model_missing)
    }

    fun chipLabel(r: Resources, chip: FormChip): String = chip.label ?: when (chip.labelCode) {
        FormChipLabel.YES -> r.getString(R.string.form_chip_yes)
        FormChipLabel.NO -> r.getString(R.string.form_chip_no)
        FormChipLabel.SKIP -> r.getString(R.string.form_chip_skip)
        FormChipLabel.CONTINUE -> r.getString(R.string.form_chip_continue)
        FormChipLabel.BY_HAND -> r.getString(R.string.form_chip_by_hand)
        FormChipLabel.SOMEONE_ELSE -> r.getString(R.string.form_chip_someone_else)
        FormChipLabel.ME_SETUP -> r.getString(R.string.form_chip_me_setup)
        FormChipLabel.CONTINUE_READING -> r.getString(R.string.form_chip_continue_reading)
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

    private fun counts(r: Resources, args: List<String>): String {
        val fields = args.getOrNull(0)?.toIntOrNull() ?: 0
        val pages = args.getOrNull(1)?.toIntOrNull() ?: 0
        return r.getQuantityString(R.plurals.form_count_fields, fields, fields) + " · " + r.getQuantityString(R.plurals.form_count_pages, pages, pages)
    }

    private fun allSet(r: Resources, args: List<String>): String {
        val base = r.format(R.string.form_all_set, args)
        if ((args.getOrNull(2)?.toIntOrNull() ?: 0) <= 0) return base
        val page = args.getOrNull(3).orEmpty()
        return base + " " + if (page.isBlank()) r.getString(R.string.form_all_set_signature) else r.format(R.string.form_all_set_signature_page, listOf(page))
    }

    private fun plural(r: Resources, @PluralsRes id: Int, count: String?): String {
        val n = count?.toIntOrNull() ?: 0
        return r.getQuantityString(id, n, n.toString())
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
