package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormRole
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Who a question is for and in which language it is asked.
 *
 * @property person how the person behind the field's role is described to the model (their relation and name), null when none.
 * @property typedSample the user's latest typed chat message: the question is asked in its language when there is one.
 * @property formLocale the form's language, used when the user has typed nothing; the UI language is the last resort.
 * @property vocabulary the words the form prints, for [QuestionLanguageCheck]; null skips the check (the line is only cleaned).
 * @property personName the name of that person alone (a name says nothing about a question's language).
 */
data class QuestionContext(
    val person: String? = null,
    val personName: String? = null,
    val typedSample: String? = null,
    val formLocale: Locale? = null,
    val vocabulary: Set<String>? = null,
)

/**
 * Words the question for one field in the user's language. The model sees the field's label, section, page, role and the
 * person behind the role (never a value) and writes one question; code keeps it only when it is a sensible line, otherwise the
 * conversation uses a template question from string resources, so it never blocks on the engine. The chat puts the section and
 * page in front of every question (see `FormChatTexts`), so the user always knows which field is meant.
 *
 * Language: the form's language (a typed "stop" or "nein" says little about the language to ask in); when it is unknown, the
 * language of the user's latest typed message, else the UI language.
 */
class FormQuestionWriter(
    private val model: FormModel,
    private val uiLanguage: () -> Locale = { Locale.getDefault() },
    private val profile: FormFillProfile = FormFillProfile(),
    private val trace: FormFillTrace = FormFillTrace.NONE,
) {

    /** The question for [field], or null when the model could not write a usable one. */
    suspend fun write(field: FormField, context: QuestionContext = QuestionContext()): String? {
        val system = "You help a person fill in a form. Write ONE short, friendly question that asks the user for " +
            "the detail the field below needs, and say whose detail it is when FOR is given. Do not answer it. Output only the question. " +
            languageRule(context)
        val user = buildString {
            append("FIELD: ").append(field.labelText)
            field.section?.takeIf { it.isNotBlank() }?.let { append("\nSECTION: ").append(it) }
            append("\nPAGE: ").append(field.page)
            field.role?.let { append("\nROLE: ").append(ROLES.getValue(it)) }
            append("\nKIND: ").append(KINDS.getValue(field.kind))
            if (field.options.isNotEmpty()) append("\nOPTIONS: ").append(field.options.joinToString(" | "))
            context.person?.takeIf { it.isNotBlank() }?.let { append("\nFOR: ").append(it) }
        }
        // The engine may still be loading or priming the chat (the first question of a chat): wait for it, and if the first
        // attempt still comes back empty, ask once more once the engine is ready. An empty line is never a question.
        var raw = attempt(system, user) { return null }
        if (raw.isNullOrBlank()) {
            trace.event("question_write", "ok=false why=no_output retry=true")
            raw = attempt(system, user) { return null }
        }
        val line = clean(raw, field)?.takeIf { passesLanguage(it, field, context) }
        if (line != null) {
            trace.event("question_write", "ok=true chars=${line.length}")
        } else {
            val why = when {
                raw.isNullOrBlank() -> "no_output"
                clean(raw, field) != null -> "rejected_language"
                else -> "rejected rawChars=${raw.length} think=${raw.contains("think", ignoreCase = true)}"
            }
            trace.event("question_write", "ok=false why=$why")
        }
        return line
    }

    /** One generation after the engine reports ready; [failed] returns from [write] with null when it cannot be loaded or throws. */
    private suspend inline fun attempt(system: String, user: String, failed: () -> Nothing): String? {
        try {
            if (model.ensureLoaded() is PamResult.Error) {
                trace.event("question_write", "ok=false why=not_ready")
                failed()
            }
            return model.write(system, user, profile.questionTokens)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trace.event("question_write", "ok=false why=exception type=${e.javaClass.simpleName}")
            failed()
        }
    }

    private fun passesLanguage(line: String, field: FormField, context: QuestionContext): Boolean {
        val vocabulary = context.vocabulary ?: return true
        return QuestionLanguageCheck.accepts(line, field.labelText, context.formLocale?.language, vocabulary, context.personName)
    }

    private fun languageRule(context: QuestionContext): String {
        // The OCR often tags no language, and the locale is then only the phone's: the label itself says what language the form is in.
        val sample = context.typedSample?.trim()?.takeIf { it.any(Char::isLetter) }?.take(SAMPLE_CHARS)
        val locale = context.formLocale ?: uiLanguage()
        val language = locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
        if (context.formLocale != null) {
            // The form's language is known (the document's stored language): say it outright, a small model follows that best.
            return "Write the question in $language, the language of the form and of the printed FIELD label. " +
                "Keep the field's label exactly as printed, do not translate it."
        }
        val fallback = if (sample != null) " (the user writes like this: \"$sample\")" else " (otherwise $language)"
        return "Write the question in the same language as the printed FIELD label$fallback. Keep the field's label exactly as printed."
    }

    /** The model's line as a question, or null when it is empty, too long, echoes the label or spans several lines. */
    private fun clean(raw: String?, field: FormField): String? {
        val line = raw?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '\'', '`', '«', '»', '*')?.trim() ?: return null
        if (line.length < MIN_CHARS || line.length > MAX_CHARS || line.none(Char::isLetter)) return null
        if (line.equals(field.labelText.trim(), ignoreCase = true)) return null
        // A reasoning trace is not a question (and a cut-off one is worse): the template question is used instead.
        if (line.contains("<think", ignoreCase = true) || line.contains("</think", ignoreCase = true)) return null
        return line
    }

    private companion object {
        const val MIN_CHARS = 6
        const val MAX_CHARS = 240
        const val SAMPLE_CHARS = 120
        val KINDS: Map<FormFieldKind, String> = mapOf(
            FormFieldKind.TEXT to "free text",
            FormFieldKind.DATE to "a date",
            FormFieldKind.CHECKBOX to "a tick box (yes or no)",
            FormFieldKind.CHOICE to "one of the printed options",
            FormFieldKind.SIGNATURE to "a signature",
            FormFieldKind.TABLE_CELL to "a table cell",
        )

        /** How a role is described to the model (English content descriptions, never shown to users). */
        val ROLES: Map<FormRole, String> = mapOf(
            FormRole.SUBJECT to "the person the form is for",
            FormRole.GUARDIAN to "the parent or guardian",
            FormRole.PAYER to "the person who pays",
            FormRole.SIGNER to "the person who signs",
            FormRole.EMERGENCY_CONTACT to "the emergency contact",
            FormRole.OTHER to "another person",
        )
    }
}
