package com.postsaimanager.core.domain.form.fill

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.form.FakePersonDataSource
import com.postsaimanager.core.model.CheckboxValue
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ReviewState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FormFillPartsTest {

    private fun field(
        id: String,
        key: String? = null,
        kind: FormFieldKind = FormFieldKind.TEXT,
        options: List<String> = emptyList(),
        value: String? = null,
        page: Int = 1,
        order: Int = 0,
        reconfirm: Boolean = false,
        skipped: Boolean = false,
        alreadyFilled: String? = null,
        review: ReviewState = ReviewState.UNREVIEWED,
    ) = FormField(
        id = id, formFillId = "fill", documentId = "doc", page = page, labelText = id, labelBox = null, fillBox = null, kind = kind,
        options = options, dataKey = key, value = value, reconfirm = reconfirm, skipped = skipped, alreadyFilled = alreadyFilled,
        reviewState = review, orderIndex = order,
    )

    // ── Answer chips ──

    private val person = mapOf(
        "full_name" to FakePersonDataSource.profile("Mohammad Al Mustermann", 1),
        "phone" to FakePersonDataSource.profile("0211 123456", 1),
        "mobile" to FakePersonDataSource.profile("0151 2345678", 1),
        "iban" to FakePersonDataSource.profile("DE89370400440532013000", 1, sensitive = true),
    )
    private val chips = AnswerChips(FakePersonDataSource(mapOf("dad" to person)))

    @Test
    fun `options, yes and no, and stored values become chips, all of them existing values`() = runTest {
        assertThat(chips.forField(field("a", options = listOf("Ja", "Nein")), "dad").map { it.label }).containsExactly("Ja", "Nein").inOrder()

        val box = chips.forField(field("b", kind = FormFieldKind.CHECKBOX), null)
        assertThat(box.map { it.labelCode }).containsExactly(FormChipLabel.YES, FormChipLabel.NO).inOrder()
        assertThat(box.map { it.arg }).containsExactly(CheckboxValue.YES, CheckboxValue.NO).inOrder()

        // Two phone numbers: both are offered for a phone field.
        assertThat(chips.forField(field("c", key = "phone"), "dad").map { it.label }).containsExactly("0211 123456", "0151 2345678").inOrder()
        assertThat(chips.forField(field("c", key = "phone"), null)).isEmpty()
    }

    @Test
    fun `the given and family name are suggested from the full name, split at the last space, never filled`() = runTest {
        assertThat(chips.forField(field("g", key = "given_name"), "dad").single().label).isEqualTo("Mohammad Al")
        assertThat(chips.forField(field("f", key = "family_name"), "dad").single().label).isEqualTo("Mustermann")
        assertThat(chips.forField(field("g", key = "given_name"), "nobody")).isEmpty()
    }

    @Test
    fun `a value to confirm is the chip, masked when sensitive`() = runTest {
        val plain = chips.forField(field("p", key = "phone", value = "0151 2345678", reconfirm = true), "dad").single()
        assertThat(plain.label).isEqualTo("0151 2345678")
        assertThat(plain.arg).isEqualTo("0151 2345678")

        val secret = chips.forField(field("i", key = "iban", value = "DE89 3704 0044 0532 0130 00", reconfirm = true), "dad").single()
        assertThat(secret.label).isEqualTo("••••3000")
        assertThat(secret.arg).isEqualTo("DE89 3704 0044 0532 0130 00")
    }

    // ── Progress and the order of questions ──

    @Test
    fun `progress counts ready, need you and signatures`() {
        val fields = listOf(
            field("a", value = "x"), field("b", value = "y"), field("c"), field("d"),
            field("e", alreadyFilled = "by hand"), field("sign", kind = FormFieldKind.SIGNATURE, page = 2),
        )

        val p = FillProgress.of(fields)

        assertThat(p).isEqualTo(FillProgress(total = 6, ready = 3, needYou = 2, signatures = 1, firstSignaturePage = 2))
    }

    @Test
    fun `questions run by page and position, skip what needs no question and never ask for the signature`() {
        val fields = listOf(
            field("p2-b", page = 2, order = 5),
            field("sign", kind = FormFieldKind.SIGNATURE, page = 1, order = 9),
            field("p1-b", page = 1, order = 3),
            field("done", value = "x", page = 1, order = 1),
            field("skipped", skipped = true, page = 1, order = 2),
            field("hand", alreadyFilled = "by hand", page = 1, order = 0),
            field("p1-a", page = 1, order = 2),
            field("still", value = "old", reconfirm = true, page = 1, order = 4),
            field("confirmed", value = "old", reconfirm = true, review = ReviewState.CONFIRMED, page = 1, order = 6),
            field("p2-a", page = 2, order = 1),
        )

        assertThat(FillProgress.openFields(fields).map { it.id }).containsExactly("p1-a", "p1-b", "still", "p2-a", "p2-b").inOrder()
    }

    // ── Stored messages ──

    @Test
    fun `a form message survives storage and an ordinary message is not one`() {
        val form = FormMessage(
            FormMessageKind.QUESTION, FormText.REMEMBER, listOf("Ahmad"),
            listOf(FormChip(FormChipAction.REMEMBER_YES, labelCode = FormChipLabel.YES), FormChip(FormChipAction.ANSWER, label = "Ja", arg = "Ja", fieldId = "f")),
            fieldId = "f",
        )

        val stored = FormMessageCodec.toMessage("m1", "conv", 5, form, content = "Soll ich das merken?")

        assertThat(stored.role).isEqualTo(MessageRole.TOOL_RESULT) // never part of the model's history
        assertThat(stored.content).isEqualTo("Soll ich das merken?")
        assertThat(FormMessageCodec.parse(stored)).isEqualTo(form)
        assertThat(FormMessageCodec.parse(stored.copy(toolName = null))).isNull()
        assertThat(FormMessageCodec.parse(stored.copy(toolArgs = "{not json"))).isNull()
    }

    // ── The question writer keeps only a sensible line ──

    private fun writerFor(line: String?) = FormQuestionWriter(FakeFormModel().also { it.question = { line } })

    @Test
    fun `the written question is kept when sensible and dropped when empty, echoing the label or too long`() = runTest {
        val f = field("Telefon", key = "phone")
        assertThat(writerFor("  \"Welche Telefonnummer sollen wir eintragen?\"\nZweite Zeile").write(f, QuestionContext(person = "Ahmad"))).isEqualTo("Welche Telefonnummer sollen wir eintragen?")
        assertThat(writerFor(null).write(f)).isNull()
        assertThat(writerFor("   ").write(f)).isNull()
        assertThat(writerFor("Telefon").write(f)).isNull()
        assertThat(writerFor("1234 5678").write(f)).isNull()
        assertThat(writerFor("x".repeat(300)).write(f)).isNull()
        assertThat(writerFor("<think> (The user wants a friendly, one-line question to fill in the field").write(f)).isNull()
    }

    @Test
    fun `a specific question of a couple of hundred characters is kept`() = runTest {
        val long = "Welche Telefonnummer der Erziehungsberechtigten soll ich für den Notfall eintragen, unter der Sie tagsüber erreichbar sind?"
        assertThat(long.length).isGreaterThan(100)
        assertThat(writerFor(long).write(field("Telefon", key = "phone"))).isEqualTo(long)
    }

    @Test
    fun `German labels get the label-language rule, the label as printed and a German question is kept`() = runTest {
        val model = FakeFormModel().also { it.question = { "Wie lautet der Vorname des Kindes?" } }
        // The OCR tagged no language, so the form's locale is only the phone's (English): the label must lead.
        val written = FormQuestionWriter(model, { java.util.Locale.ENGLISH })
            .write(field("Vorname", key = "given_name").copy(section = "Angaben zum Kind"), QuestionContext(formLocale = java.util.Locale.ENGLISH))

        assertThat(written).isEqualTo("Wie lautet der Vorname des Kindes?")
        assertThat(model.writtenSystem.single()).contains("Write the question in the same language as the printed FIELD label")
        assertThat(model.writtenSystem.single()).contains("Keep the field's label exactly as printed")
        assertThat(model.written.single()).contains("FIELD: Vorname")
        assertThat(model.written.single()).contains("SECTION: Angaben zum Kind")
    }

    @Test
    fun `the model is told the role, the person behind it, the section and the page`() = runTest {
        val model = FakeFormModel().also { it.question = { "Wie lautet der Vorname?" } }
        val f = field("Vorname", key = "first_name").copy(section = "Erziehungsberechtigte/r", page = 2, role = com.postsaimanager.core.model.FormRole.GUARDIAN)

        FormQuestionWriter(model).write(f, QuestionContext(person = "the user's parent Mohammad"))

        val told = model.written.single()
        assertThat(told).contains("SECTION: Erziehungsberechtigte/r")
        assertThat(told).contains("PAGE: 2")
        assertThat(told).contains("ROLE: the parent or guardian")
        assertThat(told).contains("FOR: the user's parent Mohammad")
    }

    @Test
    fun `the language is the user's typed message, else the form's, else the UI's`() = runTest {
        suspend fun systemFor(context: QuestionContext): String {
            val model = FakeFormModel().also { it.question = { "Welche Option gilt?" } }
            FormQuestionWriter(model, { java.util.Locale.FRENCH }).write(field("Kurs", key = "course"), context)
            return model.writtenSystem.single()
        }

        // The form's language wins: a typed "stop" or "nein" says little about the language to ask in.
        // The label's own language leads; the form's (or the UI's) is only the fallback.
        assertThat(systemFor(QuestionContext(typedSample = "stop", formLocale = java.util.Locale.GERMAN))).contains("language as the printed FIELD label (otherwise German)")
        assertThat(systemFor(QuestionContext(typedSample = "hilf mir das auszufüllen"))).contains("the user writes like this: \"hilf mir das auszufüllen\"")
        assertThat(systemFor(QuestionContext())).contains("otherwise French")
        assertThat(systemFor(QuestionContext(typedSample = "12345", formLocale = java.util.Locale.GERMAN))).contains("otherwise German")
    }

    @Test
    fun `the model is told the label, section, kind and options but no value`() = runTest {
        val model = FakeFormModel().also { it.question = { "Welche Option?" } }
        val f = field("Kurstermin", kind = FormFieldKind.CHOICE, options = listOf("Mo", "Mi"), value = "SECRET").copy(section = "Angaben")

        FormQuestionWriter(model, { java.util.Locale.GERMAN }).write(f, QuestionContext(person = "Ahmad"))

        val told = model.written.single()
        assertThat(told).contains("FIELD: Kurstermin")
        assertThat(told).contains("SECTION: Angaben")
        assertThat(told).contains("OPTIONS: Mo | Mi")
        assertThat(told).contains("FOR: Ahmad")
        assertThat(told).doesNotContain("SECRET")
    }

    // ── Intents ──

    @Test
    fun `a message is read as the intent with the best score, nothing above the threshold is an answer`() = runTest {
        val model = FakeFormModel().also { it.intents["stop"] = FormIntent.STOP }
        val classifier = FormIntentClassifier(model)

        assertThat(classifier.classify("Q", "stop")).isEqualTo(FormIntent.STOP)
        assertThat(classifier.classify("Q", "Nussallergie")).isEqualTo(FormIntent.ANSWER)

        val none = object : FormModel by model {
            override suspend fun score(system: String, context: String, statements: List<String>) =
                com.postsaimanager.core.common.result.PamResult.Success(statements.map { -1.0 })
        }
        assertThat(FormIntentClassifier(none).classify("Q", "stop")).isEqualTo(FormIntent.ANSWER)
    }

    @Test
    fun `a free answer names a candidate only when it clearly beats the others`() = runTest {
        val model = FakeFormModel().also { it.meanings["x"] = "means «B»" }
        val interpreter = AnswerInterpreter(model)

        assertThat(interpreter.pickOption("Q", "x", listOf("A", "B", "C"))).isEqualTo(1)
        assertThat(interpreter.pickOption("Q", "unknown", listOf("A", "B", "C"))).isNull()

        val tie = object : FormModel by model {
            override suspend fun score(system: String, context: String, statements: List<String>) =
                com.postsaimanager.core.common.result.PamResult.Success(statements.map { 2.0 })
        }
        assertThat(AnswerInterpreter(tie).pickOption("Q", "x", listOf("A", "B"))).isNull() // no margin: not guessed
    }
}
