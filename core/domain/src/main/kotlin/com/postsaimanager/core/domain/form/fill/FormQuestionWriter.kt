package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import java.util.Locale

/**
 * Words the question for one field in the user's language. The model sees the field's label, section and kind (and the person's
 * name), never a value, and writes one short line; code keeps it only when it is a sensible line, otherwise the conversation
 * uses a template question from string resources, so it never blocks on the engine.
 */
class FormQuestionWriter(
    private val model: FormModel,
    private val uiLanguage: () -> Locale = { Locale.getDefault() },
    private val profile: FormFillProfile = FormFillProfile(),
) {

    /** The question for [field], or null when the model could not write a usable one. */
    suspend fun write(field: FormField, subjectName: String?): String? {
        val language = uiLanguage().getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
        val system = "You help a person fill in a form. Write ONE short, friendly question in $language that asks the user for " +
            "the detail the field below needs. Do not answer it. Output only the question."
        val user = buildString {
            append("FIELD: ").append(field.labelText)
            field.section?.takeIf { it.isNotBlank() }?.let { append("\nSECTION: ").append(it) }
            append("\nKIND: ").append(KINDS.getValue(field.kind))
            if (field.options.isNotEmpty()) append("\nOPTIONS: ").append(field.options.joinToString(" | "))
            subjectName?.takeIf { it.isNotBlank() }?.let { append("\nFOR: ").append(it) }
        }
        return clean(model.write(system, user, profile.questionTokens), field)
    }

    /** The model's line as a question, or null when it is empty, too long, echoes the label or spans several lines. */
    private fun clean(raw: String?, field: FormField): String? {
        val line = raw?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '\'', '`', '«', '»', '*')?.trim() ?: return null
        if (line.length < MIN_CHARS || line.length > MAX_CHARS || line.none(Char::isLetter)) return null
        if (line.equals(field.labelText.trim(), ignoreCase = true)) return null
        return line
    }

    private companion object {
        const val MIN_CHARS = 6
        const val MAX_CHARS = 200
        val KINDS: Map<FormFieldKind, String> = mapOf(
            FormFieldKind.TEXT to "free text",
            FormFieldKind.DATE to "a date",
            FormFieldKind.CHECKBOX to "a tick box (yes or no)",
            FormFieldKind.CHOICE to "one of the printed options",
            FormFieldKind.SIGNATURE to "a signature",
            FormFieldKind.TABLE_CELL to "a table cell",
        )
    }
}
