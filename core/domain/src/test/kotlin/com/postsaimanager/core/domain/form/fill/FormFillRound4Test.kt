package com.postsaimanager.core.domain.form.fill

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.FakeEmbedder
import com.postsaimanager.core.domain.form.FormScript
import com.postsaimanager.core.model.FormAwaitKind
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.TextBounds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The fourth device pass: a stale fill silently resumed, an answer checked against the wrong field, a name read as "skip",
 * English questions on a German form, an empty first question, no chips, the OCR capture and the beta notice.
 */
class FormFillRound4Test {

    private suspend fun FillHarness.skipUntil(label: String) {
        var guard = 0
        while (guard++ < 20 && lastQuestion().first.fieldId != field(label).id) tap(FormChipLabel.SKIP.name)
        assertThat(lastQuestion().first.fieldId).isEqualTo(field(label).id)
    }

    private fun FillHarness.intentScores() = model.scored.count { it.first == FormIntents.SYSTEM }

    // ── 1. A stale fill is discarded, never resumed ──

    @Test
    fun `a fill built from another reading is discarded and read afresh, keeping what the user answered`() = runTest {
        val h = FillHarness()
        h.startForAhmad()
        h.say("nein") // the badge, answered by the user
        h.tap(FormChipLabel.NO.name) // not remembered
        val answered = h.field("Hat Ihr Kind das Seepferdchen bereits?")
        assertThat(answered.value).isEqualTo("Nein")

        // An older reading's fill: another reading key and a wrong mapping on a field nobody answered.
        h.fills.saveFill(h.fill().copy(readingKey = "v3-0123456789abcdef"))
        h.fills.saveFields("fill-doc", h.fields().map { if (it.labelText == "Anschrift") it.copy(dataKey = "full_name", role = FormRole.OTHER) else it })

        h.conversation.start("doc")

        // A fresh reading ran and ends in "Who is it for?", not in the stale question.
        assertThat(h.lastQuestion().first.text).isIn(listOf(FormText.FORM_FOUND_ASK_SUBJECT, FormText.FORM_FOUND_ASK_SUBJECT_REASON))
        assertThat(h.fill().status).isEqualTo(FormFillStatus.ASK_SUBJECT)
        assertThat(h.fill().readingKey).startsWith("v4-")
        assertThat(h.field("Anschrift").dataKey).isEqualTo("address")
        assertThat(h.fields()).hasSize(16)
        // The user's answer came along (the same label, page and section), as theirs.
        val kept = h.field("Hat Ihr Kind das Seepferdchen bereits?")
        assertThat(kept.value).isEqualTo("Nein")
        assertThat(kept.valueSource).isEqualTo(FormValueSource.USER)
        assertThat(kept.reviewState).isEqualTo(ReviewState.EDITED)
        // No profile value of the stale fill survived: the name fields are empty until a person is chosen.
        assertThat(h.field("Name des Kindes").value).isNull()
        assertThat(h.forms().count { it.text == FormText.BETA_NOTICE }).isEqualTo(2)
    }

    @Test
    fun `a fill with no recorded reading (an older build's) is discarded the same way`() = runTest {
        val h = FillHarness()
        h.startForAhmad()
        h.fills.saveFill(h.fill().copy(readingKey = null))

        h.conversation.start("doc")

        assertThat(h.lastQuestion().first.text).isIn(listOf(FormText.FORM_FOUND_ASK_SUBJECT, FormText.FORM_FOUND_ASK_SUBJECT_REASON))
        assertThat(h.fill().readingKey).startsWith("v4-")
    }

    @Test
    fun `a fill of the current reading is resumed where it stands`() = runTest {
        val h = FillHarness()
        h.startForAhmad()
        val shown = h.lastQuestion().first.fieldId

        h.conversation.start("doc")

        assertThat(h.lastQuestion().first.fieldId).isEqualTo(shown)
        assertThat(h.forms().count { it.text == FormText.BETA_NOTICE }).isEqualTo(1)
    }

    @Test
    fun `asking again on a stopped or finished fill offers to continue or start over`() = runTest {
        val h = FillHarness(profile = FormFillProfile(roundSize = 1))
        h.startForAhmad()
        h.tap(FormChipLabel.SKIP.name)
        h.tap(FormChipLabel.BY_HAND.name)
        assertThat(h.fill().status).isEqualTo(FormFillStatus.DONE)

        h.conversation.start("doc")

        val offer = h.lastQuestion().first
        assertThat(offer.text).isEqualTo(FormText.ASK_REOPEN)
        assertThat(offer.chips.map { it.action }).containsExactly(FormChipAction.REOPEN_CONTINUE, FormChipAction.START_OVER).inOrder()
        assertThat(h.fill().awaiting!!.kind).isEqualTo(FormAwaitKind.REOPEN)
        // Continue: the stored reading is kept and "Who is it for?" comes at once.
        h.tap(FormChipLabel.CONTINUE.name)
        assertThat(h.lastQuestion().first.text).isIn(listOf(FormText.FORM_FOUND_ASK_SUBJECT, FormText.FORM_FOUND_ASK_SUBJECT_REASON))
    }

    @Test
    fun `starting over discards the fill and its answers`() = runTest {
        val h = FillHarness()
        h.startForAhmad()
        h.say("nein")
        h.tap(FormChipLabel.NO.name)
        h.model.intents["Schluss"] = FormIntent.STOP
        h.say("Schluss")
        assertThat(h.fill().status).isEqualTo(FormFillStatus.STOPPED)

        h.conversation.start("doc")
        assertThat(h.lastQuestion().first.text).isEqualTo(FormText.ASK_REOPEN)
        h.tap(FormChipLabel.START_OVER.name)

        assertThat(h.lastQuestion().first.text).isIn(listOf(FormText.FORM_FOUND_ASK_SUBJECT, FormText.FORM_FOUND_ASK_SUBJECT_REASON))
        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").value).isNull()
        assertThat(h.fill().roleProfiles).isEmpty()
    }

    // ── 2. An answer belongs to the question that was shown ──

    @Test
    fun `a question is worded before the fill says it waits for its field`() = runTest {
        val h = FillHarness()
        val during = mutableListOf<String?>()
        h.model.question = { user ->
            during += kotlinx.coroutines.runBlocking { h.fills.getFill("fill-doc")?.awaiting?.fieldId }
            "Allergien: " + user.length
        }
        h.startForAhmad()
        val first = h.lastQuestion().first.fieldId
        during.clear()

        h.say("nein")
        h.tap(FormChipLabel.NO.name)

        // While the second question was written, the first was still the open one.
        assertThat(during).isNotEmpty()
        assertThat(during.first()).isNotEqualTo(h.lastQuestion().first.fieldId)
        assertThat(h.fill().awaiting!!.fieldId).isEqualTo(h.lastQuestion().first.fieldId)
        assertThat(h.fill().currentFieldId).isEqualTo(h.lastQuestion().first.fieldId)
        assertThat(first).isNotEqualTo(h.lastQuestion().first.fieldId)
    }

    @Test
    fun `an answer applies to the field of the question that was shown, even when the fill points elsewhere`() = runTest {
        val h = FillHarness()
        h.startForAhmad()
        val shown = h.field("Hat Ihr Kind das Seepferdchen bereits?")
        assertThat(h.lastQuestion().first.fieldId).isEqualTo(shown.id)
        val other = h.field("Kurstermin")
        val fill = h.fill()
        h.fills.saveFill(fill.copy(currentFieldId = other.id, awaiting = fill.awaiting!!.copy(fieldId = other.id)))

        h.say("nein")

        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").value).isEqualTo("Nein")
        assertThat(h.field("Kurstermin").value).isNull()
        assertThat(h.field("Kurstermin").skipped).isFalse()
    }

    @Test
    fun `skip leaves the shown field and asks the next one, never the same question again`() = runTest {
        val h = FillHarness()
        h.startForAhmad()
        val shown = h.field("Hat Ihr Kind das Seepferdchen bereits?")
        val fill = h.fill()
        // The fill points at another field than the question the chat shows.
        h.fills.saveFill(fill.copy(currentFieldId = h.field("Kurstermin").id, awaiting = fill.awaiting!!.copy(fieldId = h.field("Kurstermin").id)))

        h.tap(FormChipLabel.SKIP.name)

        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").skipped).isTrue()
        assertThat(h.field("Kurstermin").skipped).isFalse()
        assertThat(h.lastQuestion().first.fieldId).isNotEqualTo(shown.id)
        assertThat(h.lastQuestion().first.fieldId).isEqualTo(h.fill().awaiting!!.fieldId)
    }

    @Test
    fun `a typed skip leaves the shown field and the question that follows is another one`() = runTest {
        val h = FillHarness()
        h.model.intents["weiß ich nicht"] = FormIntent.SKIP
        h.startForAhmad()
        val shown = h.lastQuestion().first.fieldId

        h.say("weiß ich nicht")

        assertThat(h.lastQuestion().first.fieldId).isNotEqualTo(shown)
        assertThat(h.fill().awaiting!!.fieldId).isEqualTo(h.lastQuestion().first.fieldId)
    }

    @Test
    fun `after a round boundary the next question and the open field are the same field`() = runTest {
        val h = FillHarness(profile = FormFillProfile(roundSize = 2))
        h.startForAhmad()
        h.tap(FormChipLabel.SKIP.name)
        h.tap(FormChipLabel.SKIP.name)
        assertThat(h.fill().awaiting!!.kind).isEqualTo(FormAwaitKind.CONTINUE)
        assertThat(h.fill().currentFieldId).isNull()

        h.tap(FormChipLabel.CONTINUE.name)

        val question = h.lastQuestion().first
        assertThat(h.fill().awaiting!!.kind).isEqualTo(FormAwaitKind.ANSWER)
        assertThat(h.fill().awaiting!!.fieldId).isEqualTo(question.fieldId)
        assertThat(h.fill().currentFieldId).isEqualTo(question.fieldId)
        h.tap(FormChipLabel.SKIP.name)
        assertThat(h.fields().single { it.id == question.fieldId }.skipped).isTrue()
    }

    // ── 3. A typed answer is an answer ──

    @Test
    fun `a short reply the field accepts is the answer without any model call, even if the model would read it as skip`() = runTest {
        val h = FillHarness()
        h.model.intents["Mia"] = FormIntent.SKIP
        h.startForAhmad()
        h.skipUntil("Allergien / Hinweise zur Gesundheit")
        val before = h.intentScores()

        assertThat(h.say("Mia")).isEqualTo(FormRoute.HANDLED)

        assertThat(h.intentScores()).isEqualTo(before)
        assertThat(h.field("Allergien / Hinweise zur Gesundheit").value).isEqualTo("Mia")
        assertThat(h.field("Allergien / Hinweise zur Gesundheit").skipped).isFalse()
    }

    @Test
    fun `a reply worded as a question goes to intent scoring`() = runTest {
        val h = FillHarness()
        h.model.intents["Was bedeutet das?"] = FormIntent.ASK_ABOUT_FORM
        h.startForAhmad()
        h.skipUntil("Allergien / Hinweise zur Gesundheit")
        val before = h.intentScores()

        assertThat(h.say("Was bedeutet das?")).isEqualTo(FormRoute.ASK_ABOUT_FORM)

        assertThat(h.intentScores()).isEqualTo(before + 1)
        assertThat(h.field("Allergien / Hinweise zur Gesundheit").value).isNull()
    }

    @Test
    fun `a reply the field refuses is read for an intent, and an explicit skip then skips`() = runTest {
        val h = FillHarness(me = GermanSwim.me() - "email")
        h.model.intents["weiß ich nicht"] = FormIntent.SKIP
        h.startForAhmad()
        h.skipUntil("E-Mail")
        val before = h.intentScores()

        h.say("Erika Test") // not an e-mail address: scored, found to be an answer, refused with a hint
        assertThat(h.intentScores()).isEqualTo(before + 1)
        assertThat(h.lastQuestion().first.text).isEqualTo(FormText.HINT_NOT_AN_EMAIL)
        assertThat(h.lastQuestion().first.fieldId).isEqualTo(h.field("E-Mail").id)

        h.say("weiß ich nicht")
        assertThat(h.intentScores()).isEqualTo(before + 2)
        assertThat(h.field("E-Mail").skipped).isTrue()
    }

    @Test
    fun `the answer is the likeliest thing typed, so another intent needs a clear lead`() = runTest {
        val order = FormIntents.descriptions.keys.toList()
        fun classify(vararg scores: Pair<FormIntent, Double>): FormIntent {
            val byIntent = scores.toMap()
            val model = object : FormModel by FakeFormModel() {
                override suspend fun score(system: String, context: String, statements: List<String>): PamResult<List<Double>> =
                    PamResult.Success(order.map { byIntent[it] ?: -3.0 })
            }
            return kotlinx.coroutines.runBlocking { FormIntentClassifier(model).classify("question", "message") }
        }

        assertThat(classify(FormIntent.ANSWER to 0.5, FormIntent.SKIP to 1.0)).isEqualTo(FormIntent.ANSWER)
        assertThat(classify(FormIntent.ANSWER to 2.5, FormIntent.SKIP to 3.0)).isEqualTo(FormIntent.ANSWER)
        assertThat(classify(FormIntent.ANSWER to -3.0, FormIntent.SKIP to 2.0)).isEqualTo(FormIntent.SKIP)
        assertThat(classify(FormIntent.STOP to 4.0)).isEqualTo(FormIntent.STOP)
        assertThat(classify()).isEqualTo(FormIntent.ANSWER)
    }

    // ── 4. Questions are in the form's language ──

    @Test
    fun `the document's stored language is the form's language and the model is told to write it`() = runTest {
        val h = FillHarness(documentLanguage = "de", phoneLocale = Locale.US)
        h.model.question = { "Frage: Hat Ihr Kind das Seepferdchen bereits?" }

        h.startForAhmad()

        assertThat(h.fill().localeTag).isEqualTo("de")
        assertThat(h.model.writtenSystem.first()).contains("Write the question in German")
        assertThat(h.lastQuestion().second).startsWith("Frage: Hat Ihr Kind")
    }

    @Test
    fun `the stored language wins over the OCR's tags, and without one the OCR's tags and then the phone decide`() = runTest {
        val stored = FillHarness(documentLanguage = "fr", phoneLocale = Locale.US)
        stored.startForAhmad()
        assertThat(stored.fill().localeTag).isEqualTo("fr")

        val tagged = FillHarness(phoneLocale = Locale.US) // the fixture's blocks are tagged German
        tagged.startForAhmad()
        assertThat(tagged.fill().localeTag).isEqualTo("de")

        val untagged = FillHarness(pages = inventedPages.map { page -> page.map { it.copy(language = null) } }, phoneLocale = Locale.US)
        untagged.startForAhmad()
        assertThat(untagged.fill().localeTag).isEqualTo("en-US")
    }

    @Test
    fun `an English question for a German form is dropped for the German template`() = runTest {
        val h = FillHarness(documentLanguage = "de", phoneLocale = Locale.US)
        h.model.question = { "Has your child already got the swimming badge?" }

        h.startForAhmad()

        val (question, content) = h.lastQuestion()
        assertThat(content).isEmpty()
        assertThat(question.text).isEqualTo(FormText.ASK_CHOICE)
        assertThat(question.localeTag).isEqualTo("de")
        assertThat(question.args.first()).isEqualTo("Hat Ihr Kind das Seepferdchen bereits?")
    }

    @Test
    fun `a question that does not name the field it is for is dropped`() = runTest {
        val h = FillHarness(documentLanguage = "de")
        h.model.question = { "Wie lautet der Name der Mutter?" }

        h.startForAhmad()

        assertThat(h.lastQuestion().second).isEmpty()
        assertThat(h.lastQuestion().first.text).isEqualTo(FormText.ASK_CHOICE)
    }

    // ── 5. The first question waits for the engine and is never empty ──

    @Test
    fun `a first write that comes back empty is tried again once the engine is ready`() = runTest {
        val fake = FakeFormModel()
        var asked = 0
        var loads = 0
        fake.question = { if (asked++ == 0) null else "Wie lautet der Vorname des Kindes?" }
        val model = object : FormModel by fake {
            override suspend fun ensureLoaded(): PamResult<Unit> = fake.ensureLoaded().also { loads++ }
        }
        val field = com.postsaimanager.core.model.FormField(
            id = "f", formFillId = "fill", documentId = "doc", page = 1, labelText = "Vorname", labelBox = null, fillBox = null,
            kind = FormFieldKind.TEXT, section = "Angaben zum Kind",
        )

        val line = FormQuestionWriter(model).write(field, QuestionContext(formLocale = Locale.GERMAN, vocabulary = setOf("kind", "der", "des")))

        assertThat(line).isEqualTo("Wie lautet der Vorname des Kindes?")
        assertThat(asked).isEqualTo(2)
        assertThat(loads).isEqualTo(2) // ready before each try
    }

    @Test
    fun `an engine that is not ready writes nothing, and an empty line is never posted`() = runTest {
        val field = com.postsaimanager.core.model.FormField(
            id = "f", formFillId = "fill", documentId = "doc", page = 1, labelText = "Vorname", labelBox = null, fillBox = null,
            kind = FormFieldKind.TEXT,
        )
        val notReady = FakeFormModel().also { it.load = PamResult.Error(PamError.ModelNotLoaded("chat")) }
        assertThat(FormQuestionWriter(notReady).write(field)).isNull()
        assertThat(notReady.written).isEmpty()

        val h = FillHarness()
        h.model.question = { "   " }
        h.startForAhmad()
        val (question, content) = h.lastQuestion()
        assertThat(content).isEmpty()
        assertThat(question.text).isNotNull()
    }

    // ── 6. The fresh reading offers its chips, end to end ──

    private fun block(text: String, left: Float, top: Float, right: Float, height: Float = 0.014f) =
        OcrBlock(text, TextBounds(left, top, right, top + height), 0.95f, null)

    /** The drawn boxes are invisible to OCR: the rows are plain text without glyphs. */
    private val inventedPages = listOf(
        listOf(
            block("SV Musterbad 1920 e.V. · Badstraße 1 · 12345 Musterstadt", 0.08f, 0.02f, 0.70f),
            block("Anmeldung zum Schwimmkurs", 0.08f, 0.07f, 0.55f, 0.024f),
            block("Angaben zum Kind", 0.08f, 0.14f, 0.34f, 0.016f),
            block("Vorname: ______________", 0.08f, 0.18f, 0.45f),
            block("Nachname: ______________", 0.08f, 0.215f, 0.45f),
            block("Geburtsdatum: ______________", 0.08f, 0.25f, 0.45f),
            block("Kurswahl", 0.08f, 0.31f, 0.20f, 0.016f),
            block("Mo 16:00", 0.08f, 0.34f, 0.18f),
            block("Mi 15:00", 0.30f, 0.34f, 0.40f),
            block("Sa 10:00", 0.52f, 0.34f, 0.62f),
            block("Seepferdchen bereits vorhanden?", 0.08f, 0.40f, 0.48f),
            block("Ja", 0.08f, 0.43f, 0.12f),
            block("Nein", 0.30f, 0.43f, 0.36f),
            block("Seite 1 von 1", 0.43f, 0.95f, 0.57f),
        ),
    )

    private val inventedKeys = mapOf(
        "Vorname" to "given_name", "Nachname" to "family_name", "Geburtsdatum" to "birth_date",
        "Seepferdchen bereits vorhanden?" to "swim_level",
    )

    @Test
    fun `a fresh reading of the invented form asks the option rows and the badge with their options as chips`() = runTest {
        val h = FillHarness(
            pages = inventedPages,
            script = FormScript(
                keyOf = inventedKeys,
                sectionRoles = mapOf("Angaben zum Kind" to FormRole.SUBJECT),
                subjects = mapOf("Ahmad" to 3.0),
            ),
            embedder = FakeEmbedder(intent = { label -> inventedKeys[label]?.let(::listOf).orEmpty() }),
            documentLanguage = "de",
        )

        h.startForAhmad()

        val course = h.fields().single { it.labelText == "Kurswahl" }
        assertThat(course.kind).isEqualTo(FormFieldKind.CHOICE)
        assertThat(course.options).containsExactly("Mo 16:00", "Mi 15:00", "Sa 10:00").inOrder()
        assertThat(h.fields().map { it.labelText }).containsNoneOf("Mo 16:00", "Mi 15:00", "Sa 10:00", "Ja", "Nein")

        val first = h.lastQuestion().first
        assertThat(first.fieldId).isEqualTo(course.id)
        assertThat(first.chips.map(h::shown)).containsExactly("Mo 16:00", "Mi 15:00", "Sa 10:00", FormChipLabel.SKIP.name).inOrder()
        assertThat(first.chips.filter { it.action == FormChipAction.ANSWER }.map { it.arg }).containsExactly("Mo 16:00", "Mi 15:00", "Sa 10:00")
        assertThat(first.chips.all { it.fieldId == course.id }).isTrue()

        h.tap("Mi 15:00")
        assertThat(h.field("Kurswahl").value).isEqualTo("Mi 15:00")

        val badge = h.fields().single { it.labelText == "Seepferdchen bereits vorhanden?" }
        assertThat(badge.options).containsExactly("Ja", "Nein").inOrder()
        val next = h.forms().last { it.kind == FormMessageKind.QUESTION && it.fieldId == badge.id }
        assertThat(next.chips.map(h::shown)).containsExactly("Ja", "Nein", FormChipLabel.SKIP.name).inOrder()
    }

    // ── 7. The OCR capture ──

    private fun capture(allowed: List<String>, enabled: Boolean = true): Pair<FormOcrTrace, MutableList<String>> {
        val lines = mutableListOf<String>()
        return AllowlistedFormOcrTrace({ enabled }, { allowed }, lines::add) to lines
    }

    @Test
    fun `the OCR lines of an allowlisted document are logged when its reading starts`() = runTest {
        val h = FillHarness()
        val (trace, lines) = capture(listOf("other", " doc "))
        h.ocrTrace = trace

        h.conversation.start("doc")

        assertThat(lines.first()).startsWith("document=doc pages=2 blocks=")
        assertThat(lines.drop(1)).isNotEmpty()
        assertThat(lines.drop(1).all { it.matches(Regex("page=[12] box=[0-9.]+,[0-9.]+,[0-9.]+,[0-9.]+ text=.*")) }).isTrue()
        assertThat(lines.any { it.contains("text=Unterschrift") || it.contains("Name des Kindes") }).isTrue()
    }

    @Test
    fun `a document that is not on the allowlist, or a release build, logs nothing`() = runTest {
        val notListed = FillHarness()
        val (trace, lines) = capture(listOf("another-document"))
        notListed.ocrTrace = trace
        notListed.conversation.start("doc")
        assertThat(lines).isEmpty()

        val release = FillHarness()
        val (offTrace, offLines) = capture(listOf("doc"), enabled = false)
        release.ocrTrace = offTrace
        release.conversation.start("doc")
        assertThat(offLines).isEmpty()

        val empty = FillHarness()
        val (emptyTrace, emptyLines) = capture(emptyList())
        empty.ocrTrace = emptyTrace
        empty.conversation.start("doc")
        assertThat(emptyLines).isEmpty()
    }

    // ── 8. Beta ──

    @Test
    fun `the first line of a new fill says form filling is a beta`() = runTest {
        val h = FillHarness()

        h.conversation.start("doc")

        assertThat(h.forms().first().text).isEqualTo(FormText.BETA_NOTICE)
        assertThat(h.forms().first().kind).isEqualTo(FormMessageKind.STATUS)
    }
}
