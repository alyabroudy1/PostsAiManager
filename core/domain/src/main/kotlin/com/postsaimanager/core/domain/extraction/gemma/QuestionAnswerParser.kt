package com.postsaimanager.core.domain.extraction.gemma

/**
 * The model's one labelled answer ([QuestionPrompt]), split by label: the text after each `LABEL:` up to the next label (an item list
 * the model wrapped onto several lines belongs to its label). Nothing is checked here: a label the model left out is simply absent, and
 * what a value means is for [QuestionReadingBuilder].
 */
class QaAnswers(private val byLabel: Map<QaLabel, String>) {

    /** The text after [label], or null when the model gave no such line or only [QuestionPrompt.NONE_WORD]. */
    operator fun get(label: QaLabel): String? = byLabel[label]?.takeUnless { QaText.isNone(it) }

    /** The labels the model answered at all (an answer of "none" counts), for the trace. */
    val answered: Set<QaLabel> get() = byLabel.keys
}

object QuestionAnswerParser {

    private val LINE = Regex(
        "^[\\s*#>•\\-]*\\*{0,2}\\s*(${QaLabel.entries.joinToString("|") { it.name }})\\s*\\*{0,2}\\s*:\\s*\\*{0,2}\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    /** [line] as a label and its text (`**SENDER:** Name`), or null when the line starts with no label. */
    fun labelled(line: String): Pair<QaLabel, String>? {
        val m = LINE.matchEntire(line.trim()) ?: return null
        return QaLabel.entries.first { it.name.equals(m.groupValues[1], ignoreCase = true) } to m.groupValues[2].trim()
    }

    fun parse(text: String): QaAnswers {
        val fields = LinkedHashMap<QaLabel, StringBuilder>()
        var current: StringBuilder? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val labelled = labelled(line)
            if (labelled != null) {
                val (label, value) = labelled
                // The first line of a label is the answer; a repeated label adds to it (the model said the same thing twice).
                current = fields.getOrPut(label) { StringBuilder() }
                if (current.isNotEmpty()) current.append("; ")
                current.append(value)
            } else {
                // A line without a label continues the previous one: one more item of a list.
                current?.append("; ")?.append(line.trimStart('-', '*', '•', ' '))
            }
        }
        return QaAnswers(fields.mapValues { it.value.toString().trim().trim('*').trim() })
    }
}

/** The shapes the answers are written in: items, parts of an item, "none". */
object QaText {

    private val ITEM_SEPARATOR = Regex("\\s*;\\s*")
    private val PART_SEPARATOR = Regex("\\s*\\|\\s*|\\s+[—–-]\\s+|\\s*[—–]\\s*")

    /** True for an empty answer or the word the question asked for when nothing applies. */
    fun isNone(text: String?): Boolean {
        val t = text?.trim()?.trim('.', '"', '\'', '*', ' ').orEmpty()
        return t.isEmpty() || t.equals(QuestionPrompt.NONE_WORD, ignoreCase = true) || t == "-" || t == "—"
    }

    /** The items of a list answer, in order; none for "none". */
    fun items(text: String?): List<String> =
        if (isNone(text)) emptyList() else text.orEmpty().split(ITEM_SEPARATOR).map { it.trim().trimStart('-', '*', '•', ' ') }.filter { !isNone(it) }

    /** The parts of one item (`name | kind`, `date — meaning`), trimmed; an empty or "none" part stays as an empty string so positions hold. */
    fun parts(item: String): List<String> =
        item.split(PART_SEPARATOR).map { p -> p.replace(KIND_LABEL, "").trim().takeUnless { isNone(it) }.orEmpty() }

    private val KIND_LABEL = Regex("^\\s*kind\\s*[:=]\\s*", RegexOption.IGNORE_CASE)

    /**
     * The fields of a one-value line (`name | kind`, `name | phone | e-mail`) when a model also separated them with ";" or wrote a
     * "kind:" label (`name; kind: company`): split on both, a "none" field stays an empty string so positions hold.
     */
    fun fields(text: String): List<String> =
        text.split(ITEM_SEPARATOR).flatMap { parts(it) }.map { it.replace(KIND_LABEL, "") }

    /** [text] as a word of a list: its leading word, lower-cased, with everything but letters and digits removed ("pay (pay an amount)" is "pay"). */
    fun word(text: String?): String =
        text.orEmpty().trim().trimStart('*', '"', '\'', ' ').takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }.lowercase()

    /** [word] with the separators removed, the form ids are compared in ("DUE_DATE", "due date" and "due-date" are one). */
    fun key(text: String?): String = word(text).filter { it.isLetterOrDigit() }
}
