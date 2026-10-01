package com.postsaimanager.core.domain.form.fill

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.model.CheckboxValue
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.FormAwaitKind
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ReviewState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FormFillConversationTest {

    private val written = mapOf(
        "Seepferdchen" to "Hat Ahmad das Seepferdchen schon?",
        "Kurstermin" to "Welcher Kurstermin passt?",
        "Allergien" to "Hat Ahmad Allergien oder Hinweise zur Gesundheit?",
        "Ich willige" to "Dürfen Fotos veröffentlicht werden?",
    )

    private fun FillHarness.writeQuestions() {
        model.question = { user -> written.entries.firstOrNull { user.contains(it.key) }?.value }
    }

    // ── The whole flow on the German swim-course form ──

    @Test
    fun `the swim course form is filled for Ahmad from the first tap to done`() = runTest {
        val h = FillHarness()
        h.writeQuestions()

        // Understanding runs on demand, with progress lines, and ends in "who is it for" with the quoted reason.
        h.conversation.start("doc")
        val (subject, _) = h.lastQuestion()
        assertThat(subject.text).isEqualTo(FormText.FORM_FOUND_ASK_SUBJECT_REASON)
        assertThat(subject.args).containsExactly("16", "2", "Kinder 6–10 Jahre · Kursbeginn im Herbst", "Ahmad").inOrder()
        assertThat(subject.chips.map(h::shown)).containsExactly("Ahmad", "Me", FormChipLabel.SOMEONE_ELSE.name).inOrder()
        assertThat(h.fill().status).isEqualTo(FormFillStatus.ASK_SUBJECT)
        assertThat(h.fill().awaiting!!.kind).isEqualTo(FormAwaitKind.SUBJECT)
        val progress = h.forms().filter { it.text == FormText.UNDERSTANDING }
        assertThat(progress).hasSize(1) // one line, updated in place
        assertThat(progress.single().args).containsExactly("5", "5").inOrder()

        // One tap on Ahmad: his father ("Me", the only guardian) is settled without a question, and the form is filled from both.
        h.tap("Ahmad")
        val fill = h.fill()
        assertThat(fill.roleProfiles).containsAtLeast(FormRole.SUBJECT, "ahmad", FormRole.GUARDIAN, "me")
        assertThat(fill.roleProfiles[FormRole.PAYER]).isEqualTo("me")
        assertThat(fill.confirmedRoles).containsAtLeast(FormRole.SUBJECT, FormRole.GUARDIAN)
        assertThat(h.field("Name des Kindes").value).isEqualTo("Ahmad Mustermann")
        assertThat(h.field("Geburtsdatum").value).isEqualTo("12.03.2019")
        assertThat(h.field("Name der Erziehungsberechtigten").value).isEqualTo("Mohammad Mustermann")
        assertThat(h.field("IBAN").value).isEqualTo("DE89 3704 0044 0532 0130 00")
        assertThat(h.field("Ort, Datum").value).isEqualTo("Beispieldorf")
        assertThat(h.field("Ort, Datum").valueSource).isEqualTo(FormValueSource.TODAY)
        val card = h.forms().single { it.kind == FormMessageKind.CARD }
        assertThat(card.text).isEqualTo(FormText.FILLED_INTRO)
        assertThat(card.args).containsExactly("11", "16").inOrder()

        // Question 1: the badge (printed options Ja / Nein), worded by the model, with the options as chips.
        var (question, text) = h.lastQuestion()
        assertThat(text).isEqualTo("Hat Ahmad das Seepferdchen schon?")
        assertThat(question.fieldId).isEqualTo(h.field("Hat Ihr Kind das Seepferdchen bereits?").id)
        assertThat(question.chips.map(h::shown)).containsExactly("Ja", "Nein", FormChipLabel.SKIP.name).inOrder()
        assertThat(h.say("nein")).isEqualTo(FormRoute.HANDLED)
        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").value).isEqualTo("Nein")
        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").valueSource).isEqualTo(FormValueSource.USER)

        // "Remember for Ahmad?" and the answer is stored as a fact from this form.
        question = h.lastQuestion().first
        assertThat(question.text).isEqualTo(FormText.REMEMBER)
        assertThat(question.args).containsExactly("Ahmad")
        h.tap(FormChipLabel.YES.name)
        val fact = h.facts.facts("ahmad").single()
        assertThat(fact.key).isEqualTo("swim_level")
        assertThat(fact.value).isEqualTo("Nein")
        assertThat(fact.source).isEqualTo(FactSource.FORM_ANSWER)
        assertThat(fact.sourceDocumentId).isEqualTo("doc")

        // Question 2: the course slot, answered by "Mittwoch", checked against the printed options.
        text = h.lastQuestion().second
        assertThat(text).isEqualTo("Welcher Kurstermin passt?")
        assertThat(h.lastQuestion().first.chips.map(h::shown)).containsAtLeast("Montag 16:00 Uhr", "Mittwoch 15:00 Uhr", "Samstag 10:00 Uhr")
        h.say("Mittwoch")
        assertThat(h.field("Kurstermin").value).isEqualTo("Mittwoch 15:00 Uhr")
        assertThat(h.lastQuestion().second).isEqualTo("Hat Ahmad Allergien oder Hinweise zur Gesundheit?") // no key: nothing to remember

        // Question 3: allergies, a sensitive detail: written, then offered for Ahmad, and declined.
        h.say("Nussallergie")
        assertThat(h.field("Allergien / Hinweise zur Gesundheit").value).isEqualTo("Nussallergie")
        assertThat(h.lastQuestion().first.text).isEqualTo(FormText.REMEMBER)
        h.tap(FormChipLabel.NO.name)
        assertThat(h.facts.facts("ahmad").map { it.key }).containsExactly("swim_level")

        // Question 4: the photo consent (a tick box with no printed options) answered with a chip.
        val photo = h.lastQuestion()
        assertThat(photo.second).isEqualTo("Dürfen Fotos veröffentlicht werden?")
        assertThat(photo.first.chips.map(h::shown)).containsExactly(FormChipLabel.YES.name, FormChipLabel.NO.name, FormChipLabel.SKIP.name).inOrder()
        h.tap(FormChipLabel.YES.name)
        assertThat(h.field("Ich willige in die Veröffentlichung von Fotos ein").value).isEqualTo(CheckboxValue.YES)

        // Done: the card says where it all stands, and only the signature is left.
        assertThat(h.fill().status).isEqualTo(FormFillStatus.DONE)
        assertThat(h.fill().awaiting).isNull()
        val done = h.forms().last { it.kind == FormMessageKind.CARD }
        assertThat(done.text).isEqualTo(FormText.ALL_SET)
        assertThat(done.args).containsExactly("15", "16", "1", "2").inOrder()
        assertThat(h.field("Unterschrift").value).isNull()
        assertThat(h.userTexts(h.messages())).containsAtLeast("Ahmad", "nein", "Mittwoch", "Nussallergie")
    }

    // ── Entry, subject and roles ──

    @Test
    fun `a message asking to fill the form starts it in any document chat, an ordinary question does not`() = runTest {
        // No embedding model on this device: every message of a non-form document goes to the model's own yes/no score.
        val h = FillHarness(documentIsForm = false, embedder = com.postsaimanager.core.domain.form.FakeEmbedder(ready = false))
        h.model.fillRequests += "fülle das für Ahmad aus"

        assertThat(h.say("Was kostet der Kurs?")).isEqualTo(FormRoute.NOT_FOR_FORM)
        assertThat(h.messages()).isEmpty()

        assertThat(h.say("fülle das für Ahmad aus")).isEqualTo(FormRoute.HANDLED)
        assertThat(h.userTexts(h.messages())).containsExactly("fülle das für Ahmad aus")
        assertThat(h.lastQuestion().first.text).isEqualTo(FormText.FORM_FOUND_ASK_SUBJECT_REASON)
        assertThat(h.fill().status).isEqualTo(FormFillStatus.ASK_SUBJECT)
    }

    @Test
    fun `on a document that is not a form the embedding model gates the model call`() = runTest {
        val near = com.postsaimanager.core.domain.form.FakeEmbedder(intent = { text ->
            if (text == "fülle das aus" || text in FormIntents.fillRequestExamples) listOf("swim_level") else emptyList()
        })
        val h = FillHarness(documentIsForm = false, embedder = near)
        h.model.fillRequests += "fülle das aus"

        assertThat(h.say("Wie spät ist es?")).isEqualTo(FormRoute.NOT_FOR_FORM)
        assertThat(h.model.scored).isEmpty() // far from any fill request: the model was never asked

        assertThat(h.say("fülle das aus")).isEqualTo(FormRoute.HANDLED)
        assertThat(h.model.scored).isNotEmpty()
    }

    @Test
    fun `two guardians are asked, the chosen one fills the parent section and pays`() = runTest {
        val h = FillHarness(withPartner = true)
        h.conversation.start("doc")
        h.tap("Ahmad")

        val ask = h.lastQuestion().first
        assertThat(ask.text).isEqualTo(FormText.ASK_GUARDIAN)
        assertThat(ask.chips.map(h::shown)).containsExactly("Me", "Anna", FormChipLabel.SOMEONE_ELSE.name).inOrder()
        assertThat(h.fill().status).isEqualTo(FormFillStatus.ASK_ROLE)
        assertThat(h.field("Name der Erziehungsberechtigten").value).isNull() // nothing is guessed

        h.tap("Anna")
        assertThat(h.field("Name der Erziehungsberechtigten").value).isEqualTo("Anna Mustermann")
        assertThat(h.fill().roleProfiles[FormRole.GUARDIAN]).isEqualTo("anna")
        assertThat(h.fill().roleProfiles[FormRole.PAYER]).isEqualTo("anna")
        assertThat(h.fill().confirmedRoles).contains(FormRole.GUARDIAN)
    }

    @Test
    fun `someone else gets no profile data and no sensitive value`() = runTest {
        val h = FillHarness()
        h.conversation.start("doc")
        h.tap(FormChipLabel.SOMEONE_ELSE.name)

        assertThat(h.fill().roleProfiles).isEmpty()
        assertThat(h.fill().confirmedRoles).isEmpty()
        // Nobody's data: only the place of signing (the Me city, from today) is filled; no name, no IBAN.
        assertThat(h.fields().mapNotNull { it.value }).containsExactly("Beispieldorf")
        assertThat(h.field("IBAN").value).isNull()
    }

    @Test
    fun `changing the subject clears the answers about the first one and fills for the other`() = runTest {
        val h = FillHarness()
        h.model.intents["mach es für mich"] = FormIntent.CHANGE_SUBJECT
        h.model.meanings["mach es für mich"] = "names the user Me"
        h.startForAhmad()
        h.say("nein")
        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").value).isEqualTo("Nein")

        h.say("mach es für mich")

        assertThat(h.fill().roleProfiles[FormRole.SUBJECT]).isEqualTo("me")
        assertThat(h.field("Name des Kindes").value).isEqualTo("Mohammad Mustermann")
        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").value).isNull() // an answer about Ahmad
        assertThat(h.fill().status).isEqualTo(FormFillStatus.ASKING)
    }

    // ── Verified answers ──

    @Test
    fun `an answer that is not an option is asked again once with a hint, then left for the user`() = runTest {
        val h = FillHarness()
        h.startForAhmad()
        h.say("ja")
        h.tap(FormChipLabel.NO.name) // not remembered
        assertThat(h.lastQuestion().first.fieldId).isEqualTo(h.field("Kurstermin").id)

        h.say("Dienstag")
        val hint = h.lastQuestion().first
        assertThat(hint.text).isEqualTo(FormText.HINT_NOT_AN_OPTION)
        assertThat(hint.fieldId).isEqualTo(h.field("Kurstermin").id)
        assertThat(hint.chips.map(h::shown)).contains("Samstag 10:00 Uhr")
        assertThat(h.field("Kurstermin").value).isNull()

        h.say("Freitag")
        assertThat(h.field("Kurstermin").skipped).isTrue()
        assertThat(h.field("Kurstermin").value).isNull()
        assertThat(h.forms().any { it.text == FormText.LEFT_FOR_YOU }).isTrue()
        assertThat(h.lastQuestion().first.fieldId).isEqualTo(h.field("Allergien / Hinweise zur Gesundheit").id)
    }

    @Test
    fun `a hint lets the second try succeed`() = runTest {
        val h = FillHarness()
        h.startForAhmad()
        h.say("ja")
        h.tap(FormChipLabel.NO.name)

        h.say("Dienstag")
        h.say("Samstag")

        assertThat(h.field("Kurstermin").value).isEqualTo("Samstag 10:00 Uhr")
        assertThat(h.field("Kurstermin").skipped).isFalse()
    }

    @Test
    fun `a free answer is mapped to an option by the model and the stored value is the printed option`() = runTest {
        val h = FillHarness()
        h.model.meanings["am Wochenende"] = "means «Samstag 10:00 Uhr»"
        h.startForAhmad()
        h.say("ja")
        h.tap(FormChipLabel.NO.name)

        h.say("am Wochenende")

        assertThat(h.field("Kurstermin").value).isEqualTo("Samstag 10:00 Uhr")
        assertThat(h.field("Kurstermin").reviewState).isEqualTo(ReviewState.EDITED)
    }

    @Test
    fun `a tick box takes a free yes or no mapped by the model`() = runTest {
        val h = FillHarness()
        h.model.meanings["gerne"] = "means yes"
        h.startForAhmad()
        h.say("ja"); h.tap(FormChipLabel.NO.name)
        h.say("Samstag")
        h.say("keine"); h.tap(FormChipLabel.NO.name)

        h.say("gerne")

        assertThat(h.field("Ich willige in die Veröffentlichung von Fotos ein").value).isEqualTo(CheckboxValue.YES)
        assertThat(h.fill().status).isEqualTo(FormFillStatus.DONE)
    }

    @Test
    fun `a changed value is verified, replaces the filled one and is remembered in the profile`() = runTest {
        val h = FillHarness()
        h.model.intents["die Telefonnummer stimmt nicht"] = FormIntent.CHANGE_VALUE
        h.model.meanings["die Telefonnummer stimmt nicht"] = "«Telefon (Notfall)»"
        h.startForAhmad()
        val first = h.lastQuestion().first.fieldId

        h.say("die Telefonnummer stimmt nicht")
        val ask = h.lastQuestion().first
        assertThat(ask.fieldId).isEqualTo(h.field("Telefon (Notfall)").id)
        assertThat(ask.chips.map(h::shown)).contains("0151 2345678") // the stored number as a chip

        h.say("12")
        assertThat(h.lastQuestion().first.text).isEqualTo(FormText.HINT_NOT_A_PHONE)
        h.say("0170 9998887")
        assertThat(h.field("Telefon (Notfall)").value).isEqualTo("0170 9998887")
        assertThat(h.field("Telefon (Notfall)").valueSource).isEqualTo(FormValueSource.USER)
        assertThat(h.lastQuestion().first.text).isEqualTo(FormText.REMEMBER)
        assertThat(h.lastQuestion().first.args).containsExactly("Me")
        h.tap(FormChipLabel.YES.name)
        assertThat(h.profiles.updated.last().phone).isEqualTo("0170 9998887") // a profile column, not a fact
        assertThat(h.facts.facts("me")).isEmpty()
        assertThat(first).isNotNull() // the open question comes back afterwards
        assertThat(h.lastQuestion().first.fieldId).isEqualTo(first)
    }

    @Test
    fun `a stored value older than its time is asked as still right and confirming keeps its source`() = runTest {
        val h = FillHarness(me = GermanSwim.me(phoneAt = GermanSwim.OLD))
        h.conversation.start("doc")
        h.tap("Ahmad")

        val phone = h.field("Telefon (Notfall)")
        assertThat(phone.reconfirm).isTrue()
        assertThat(phone.value).isEqualTo("0151 2345678")
        // Questions run by page and position: the reconfirmation sits between the badge and the others by its position, so reach it.
        h.say("ja"); h.tap(FormChipLabel.NO.name)
        h.say("Samstag")
        h.say("keine"); h.tap(FormChipLabel.NO.name)
        val q = h.lastQuestion().first
        assertThat(q.text).isEqualTo(FormText.STILL_RIGHT)
        assertThat(q.args).containsExactly("Telefon (Notfall)", "0151 2345678").inOrder()
        assertThat(q.chips.first().label).isEqualTo("0151 2345678")

        h.tap("0151 2345678")

        val confirmed = h.field("Telefon (Notfall)")
        assertThat(confirmed.reviewState).isEqualTo(ReviewState.CONFIRMED)
        assertThat(confirmed.valueSource).isEqualTo(FormValueSource.PROFILE)
        assertThat(confirmed.reconfirm).isFalse()
        assertThat(h.lastQuestion().first.text).isNotEqualTo(FormText.REMEMBER)
    }

    // ── Interrupts ──

    @Test
    fun `a question about the form is answered by the chat and the fill then asks again`() = runTest {
        val h = FillHarness()
        h.writeQuestions()
        h.model.intents["was heißt Haftung?"] = FormIntent.ASK_ABOUT_FORM
        h.startForAhmad()
        val before = h.messages().size

        assertThat(h.say("was heißt Haftung?")).isEqualTo(FormRoute.ASK_ABOUT_FORM)
        assertThat(h.messages()).hasSize(before) // not stored here: the normal chat stores and answers it

        h.conversation.reask("doc")
        val after = h.messages()
        assertThat(after).hasSize(before + 1)
        assertThat(after.last().content).isEqualTo("Hat Ahmad das Seepferdchen schon?")
        assertThat(after.last().id).isNotEqualTo(after[after.size - 2].id)
        assertThat(h.fill().awaiting!!.kind).isEqualTo(FormAwaitKind.ANSWER)
    }

    @Test
    fun `skip leaves a field for the user and stop ends the conversation`() = runTest {
        val h = FillHarness()
        h.model.intents["weiß ich nicht"] = FormIntent.SKIP
        h.model.intents["lass uns aufhören"] = FormIntent.STOP
        h.startForAhmad()

        h.say("weiß ich nicht")
        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").skipped).isTrue()
        assertThat(h.lastQuestion().first.fieldId).isEqualTo(h.field("Kurstermin").id)

        h.say("lass uns aufhören")
        assertThat(h.fill().status).isEqualTo(FormFillStatus.STOPPED)
        assertThat(h.forms().last().text).isEqualTo(FormText.STOPPED)
        // Typing "fill the form" again opens it again.
        h.model.fillRequests += "nochmal"
        assertThat(h.say("nochmal")).isEqualTo(FormRoute.HANDLED)
        assertThat(h.lastQuestion().first.text).isEqualTo(FormText.FORM_FOUND_ASK_SUBJECT)
    }

    @Test
    fun `after a round the user decides to continue or to do the rest by hand`() = runTest {
        val h = FillHarness(profile = FormFillProfile(roundSize = 2))
        h.startForAhmad()

        h.say("ja"); h.tap(FormChipLabel.NO.name)
        h.say("Samstag")
        val more = h.lastQuestion().first
        assertThat(more.text).isEqualTo(FormText.MORE_QUESTIONS)
        assertThat(more.args).containsExactly("2")
        assertThat(more.chips.map { it.action }).containsExactly(FormChipAction.CONTINUE, FormChipAction.BY_HAND).inOrder()
        assertThat(h.fill().awaiting!!.kind).isEqualTo(FormAwaitKind.CONTINUE)

        h.tap(FormChipLabel.BY_HAND.name)

        assertThat(h.fill().status).isEqualTo(FormFillStatus.DONE)
        assertThat(h.field("Allergien / Hinweise zur Gesundheit").skipped).isTrue()
        assertThat(h.field("Ich willige in die Veröffentlichung von Fotos ein").skipped).isTrue()
        assertThat(h.forms().any { it.text == FormText.BY_HAND && it.args == listOf("2") }).isTrue()
        val card = h.forms().last { it.kind == FormMessageKind.CARD }
        assertThat(card.text).isEqualTo(FormText.ALL_SET)
        assertThat(card.args).containsExactly("13", "16", "1", "2").inOrder()
    }

    @Test
    fun `continuing asks the next round`() = runTest {
        val h = FillHarness(profile = FormFillProfile(roundSize = 2))
        h.startForAhmad()
        h.say("ja"); h.tap(FormChipLabel.NO.name)
        h.say("Samstag")

        h.tap(FormChipLabel.CONTINUE.name)

        assertThat(h.fill().roundAsked).isEqualTo(0)
        assertThat(h.lastQuestion().first.fieldId).isEqualTo(h.field("Allergien / Hinweise zur Gesundheit").id)
    }

    // ── Persistence and failure ──

    @Test
    fun `the conversation resumes from the stored state after the process died`() = runTest {
        val h = FillHarness()
        h.startForAhmad()
        h.say("ja"); h.tap(FormChipLabel.YES.name)
        assertThat(h.facts.facts("ahmad")).hasSize(1)

        h.conversation = h.newConversation() // a new process: only the database survived

        h.say("Samstag")
        assertThat(h.field("Kurstermin").value).isEqualTo("Samstag 10:00 Uhr")
        h.say("Nuss"); h.tap(FormChipLabel.NO.name)
        h.tap(FormChipLabel.NO.name)
        assertThat(h.fill().status).isEqualTo(FormFillStatus.DONE)
        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").value).isEqualTo("Ja")
    }

    @Test
    fun `an interrupted understanding runs again when the chat opens, and failure never blocks`() = runTest {
        val h = FillHarness()
        h.session.openFailsWith = PamError.InferenceError("engine down")

        h.conversation.start("doc")
        assertThat(h.forms().last().text).isEqualTo(FormText.UNDERSTANDING_FAILED)
        assertThat(h.fill().status).isEqualTo(FormFillStatus.UNDERSTANDING)
        assertThat(h.fields()).isEmpty()

        h.session.openFailsWith = null
        h.conversation.resume("doc")

        assertThat(h.fill().status).isEqualTo(FormFillStatus.ASK_SUBJECT)
        assertThat(h.fields()).hasSize(16)
    }

    @Test
    fun `without a model the conversation says so and starts again once there is one`() = runTest {
        val h = FillHarness()
        h.model.load = com.postsaimanager.core.common.result.PamResult.Error(PamError.ModelNotLoaded("chat"))

        h.conversation.start("doc")
        assertThat(h.forms().last().text).isEqualTo(FormText.NO_MODEL)

        h.model.load = com.postsaimanager.core.common.result.PamResult.Success(Unit)
        h.conversation.start("doc")
        assertThat(h.lastQuestion().first.text).isEqualTo(FormText.FORM_FOUND_ASK_SUBJECT_REASON)
    }

    @Test
    fun `when the model cannot write a question a template question is asked`() = runTest {
        val h = FillHarness() // question = null: the model writes nothing
        h.startForAhmad()

        val (question, content) = h.lastQuestion()
        assertThat(content).isEmpty()
        assertThat(question.text).isEqualTo(FormText.ASK_CHOICE)
        assertThat(question.args).containsExactly("Hat Ihr Kind das Seepferdchen bereits?")
        h.say("nein"); h.tap(FormChipLabel.NO.name)
        h.say("Samstag")
        h.say("keine"); h.tap(FormChipLabel.NO.name)
        assertThat(h.lastQuestion().first.text).isEqualTo(FormText.ASK_YES_NO)
    }

    @Test
    fun `an engine that fails every score still lets the conversation go on`() = runTest {
        val h = FillHarness()
        val failing = h.model.failScoring()
        val conversation = FormFillConversation(
            fills = h.fills, conversations = h.conversations, documents = h.documents, profiles = h.profiles, people = h.people,
            guardiansOf = com.postsaimanager.core.domain.form.GuardiansOfUseCase(h.profiles),
            remember = com.postsaimanager.core.domain.form.RememberDetailUseCase(h.profiles, h.facts),
            understand = com.postsaimanager.core.domain.form.UnderstandFormUseCase(h.session, { s, u -> "<s>$s|$u<u>" to "<a>" }, GermanSwim.embedder),
            model = failing, classifier = FormIntentClassifier(failing), detector = FillRequestDetector(failing, GermanSwim.embedder),
            interpreter = AnswerInterpreter(failing), writer = FormQuestionWriter(failing), answerChips = AnswerChips(h.people),
            fillValues = com.postsaimanager.core.domain.form.FillValues(h.people), clock = { h.nowMs },
            today = { java.time.LocalDate.of(2026, 10, 1) }, fallbackLocale = { java.util.Locale.GERMANY },
        )
        h.conversation = conversation
        h.startForAhmad()

        assertThat(h.say("Ja")).isEqualTo(FormRoute.HANDLED) // no intent could be scored: a plain answer
        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").value).isEqualTo("Ja")
    }

    @Test
    fun `a chip of an earlier question is ignored`() = runTest {
        val h = FillHarness()
        h.startForAhmad()
        val staleChip = h.lastQuestion().first.chips.first { it.arg == "Ja" }
        h.say("nein"); h.tap(FormChipLabel.NO.name)
        val before = h.messages().size

        h.tap(staleChip)

        assertThat(h.messages()).hasSize(before)
        assertThat(h.field("Hat Ihr Kind das Seepferdchen bereits?").value).isEqualTo("Nein")
    }

    // ── The AI never writes a value ──

    @Test
    fun `no value in the form comes from the model, whatever it writes or scores`() = runTest {
        val h = FillHarness()
        h.model.question = { "INVENTED 4711 Max Mustermann DE00 0000" }
        h.model.meanings["gerne"] = "means yes"
        h.startForAhmad()
        h.say("nein"); h.tap(FormChipLabel.NO.name)
        h.say("Mittwoch")
        h.say("Nussallergie"); h.tap(FormChipLabel.NO.name)
        h.say("gerne")

        val stored = (GermanSwim.ahmad().values + GermanSwim.me().values).map { it.value }.toSet() + setOf(
            "12.03.2019", "Musterstraße 12, 54321 Beispieldorf", "DE89 3704 0044 0532 0130 00", "Beispieldorf",
            "Nein", "Mittwoch 15:00 Uhr", "Nussallergie", CheckboxValue.YES,
        )
        val values = h.fields().mapNotNull { it.value }
        assertThat(values).isNotEmpty()
        assertThat(stored).containsAtLeastElementsIn(values.toSet())
        assertThat(values.none { it.contains("INVENTED") }).isTrue()
        // What the model wrote is only ever the text of a question message.
        assertThat(h.messages().filter { it.content.contains("INVENTED") }.all { it.role == MessageRole.TOOL_RESULT }).isTrue()
        assertThat(h.messages().filter { it.role == MessageRole.USER }.none { it.content.contains("INVENTED") }).isTrue()
    }

    @Test
    fun `the model is only ever told labels, never a stored value`() = runTest {
        val h = FillHarness()
        h.startForAhmad()
        h.say("nein")

        val told = h.model.written + h.model.scored.map { it.second }
        assertThat(told.none { it.contains("Mustermann") || it.contains("DE89") || it.contains("0151") }).isTrue()
    }
}
