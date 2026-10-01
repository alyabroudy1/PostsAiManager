package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.form.AnswerVerifiers
import com.postsaimanager.core.domain.form.FillContext
import com.postsaimanager.core.domain.form.FillValues
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.form.FormLocales
import com.postsaimanager.core.domain.form.FormProgress
import com.postsaimanager.core.domain.form.FormValueFormatter
import com.postsaimanager.core.domain.form.GuardiansOfUseCase
import com.postsaimanager.core.domain.form.PersonDataSource
import com.postsaimanager.core.domain.form.RememberDetailUseCase
import com.postsaimanager.core.domain.form.Rejection
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.form.SubjectSuggestion
import com.postsaimanager.core.domain.form.UnderstandFormRequest
import com.postsaimanager.core.domain.form.UnderstandFormUseCase
import com.postsaimanager.core.domain.form.Verification
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.FormFillRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.AiModelType
import com.postsaimanager.core.model.CheckboxValue
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.FormAwaitKind
import com.postsaimanager.core.model.FormAwaiting
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.FormValueKind
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.model.ReviewState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/** What the conversation did with a message the user typed in a document chat. */
enum class FormRoute {
    /** Nothing to do with a form fill: the normal chat answers it. */
    NOT_FOR_FORM,

    /** The conversation took it (and stored it as the user's message). */
    HANDLED,

    /** A question about the form: the normal grounded chat answers it, then [FormFillConversation.reask] resumes the fill. */
    ASK_ABOUT_FORM,
}

/**
 * The form-filling conversation of a document's AI chat: a state machine DRIVEN BY CODE over the stored fields, persisted in
 * `form_fills` (status, the open question, the chosen people, the round), so it resumes after a restart.
 *
 * ```
 * UNDERSTANDING -> ASK_SUBJECT -> (ASK_ROLE) -> fill from profiles -> ASKING (<= 5 per round) -> DONE | STOPPED
 * ```
 *
 * The AI does the language: [UnderstandFormUseCase] reads the form, [FormQuestionWriter] words each question, [FormIntentClassifier]
 * reads what a typed message means and [AnswerInterpreter] maps a free answer onto an option, yes/no or a person, all by scoring
 * or writing a question line. CODE verifies and writes every value: a value comes from [FillValues] (a person's stored detail),
 * from the user's answer after [AnswerVerifiers], from an option printed on the form, or from today's date. Nothing the model
 * writes is ever stored as a value.
 *
 * Everything the conversation says is a stored message (see [FormMessage]) with codes, not UI text. One conversation runs at a time.
 */
class FormFillConversation(
    private val fills: FormFillRepository,
    private val conversations: ConversationRepository,
    private val documents: DocumentRepository,
    private val profiles: ProfileRepository,
    private val people: PersonDataSource,
    private val guardiansOf: GuardiansOfUseCase,
    private val remember: RememberDetailUseCase,
    private val understand: UnderstandFormUseCase,
    private val model: FormModel,
    private val classifier: FormIntentClassifier,
    private val detector: FillRequestDetector,
    private val interpreter: AnswerInterpreter,
    private val writer: FormQuestionWriter,
    private val answerChips: AnswerChips,
    private val fillValues: FillValues,
    private val profile: FormFillProfile = FormFillProfile(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    private val fallbackLocale: () -> Locale = Locale::getDefault,
) {

    private val lock = Mutex()
    private var lastStamp = 0L

    // ── Entry points ──

    /** "Help me fill it": starts the document's fill, or picks an existing one up where it stands. */
    suspend fun start(documentId: String) = lock.withLock { startLocked(documentId) }

    /** The chat was opened: an understanding that was interrupted (the user left, the process died) runs again. */
    suspend fun resume(documentId: String) = lock.withLock {
        val fill = fills.fillForDocument(documentId) ?: return@withLock
        if (fill.status == FormFillStatus.UNDERSTANDING) understandForm(fill)
    }

    /** A message the user typed in the document chat. */
    suspend fun route(documentId: String, text: String): FormRoute = lock.withLock {
        val fill = fills.fillForDocument(documentId)
        if (fill == null || fill.status !in ACTIVE) {
            if (!detector.asksForFill(text, documentIsForm(documentId))) return@withLock FormRoute.NOT_FOR_FORM
            userSaid(documentId, text)
            startLocked(documentId)
            return@withLock FormRoute.HANDLED
        }
        val intent = classifier.classify(describeAwaiting(fill), text)
        if (intent == FormIntent.ASK_ABOUT_FORM) return@withLock FormRoute.ASK_ABOUT_FORM
        userSaid(documentId, text)
        when (intent) {
            FormIntent.ANSWER -> answerTyped(fill, text)
            FormIntent.CHANGE_SUBJECT -> changeSubject(fill, text)
            FormIntent.CHANGE_VALUE -> changeValue(fill, text)
            FormIntent.SKIP -> skipOpenQuestion(fill)
            FormIntent.STOP -> stop(fill)
            FormIntent.ASK_ABOUT_FORM -> Unit
        }
        FormRoute.HANDLED
    }

    /** The user tapped [chip]; [shownText] is its label as the user saw it, stored as the user's message. */
    suspend fun chip(documentId: String, chip: FormChip, shownText: String) = lock.withLock {
        val fill = fills.fillForDocument(documentId) ?: return@withLock
        val awaiting = fill.awaiting ?: return@withLock
        val matches = when (chip.action) {
            FormChipAction.ANSWER, FormChipAction.SKIP ->
                awaiting.kind == FormAwaitKind.ANSWER && (chip.fieldId == null || chip.fieldId == awaiting.fieldId)
            FormChipAction.PERSON -> awaiting.kind == FormAwaitKind.SUBJECT || awaiting.kind == FormAwaitKind.ROLE
            FormChipAction.REMEMBER_YES, FormChipAction.REMEMBER_NO -> awaiting.kind == FormAwaitKind.REMEMBER
            FormChipAction.CONTINUE, FormChipAction.BY_HAND -> awaiting.kind == FormAwaitKind.CONTINUE
        }
        if (!matches) return@withLock // a chip of an earlier question
        userSaid(documentId, shownText)
        when (chip.action) {
            FormChipAction.ANSWER -> answerField(fill, awaiting, Typed(chip.arg.orEmpty(), chip = true))
            FormChipAction.SKIP -> skipOpenQuestion(fill)
            FormChipAction.PERSON -> choosePerson(fill, awaiting, chip.arg?.takeIf { it.isNotBlank() })
            FormChipAction.REMEMBER_YES -> rememberAnswer(fill, awaiting, yes = true)
            FormChipAction.REMEMBER_NO -> rememberAnswer(fill, awaiting, yes = false)
            FormChipAction.CONTINUE -> continueRound(fill)
            FormChipAction.BY_HAND -> finish(fill, byHand = true)
        }
    }

    /** After the chat answered a question about the form: asks the open question again, so the fill goes on. */
    suspend fun reask(documentId: String) = lock.withLock {
        val fill = fills.fillForDocument(documentId) ?: return@withLock
        if (fill.status !in ACTIVE) return@withLock
        val id = conversationId(documentId)
        val last = conversations.getMessages(id).first().lastOrNull { FormMessageCodec.parse(it)?.kind == FormMessageKind.QUESTION }
            ?: return@withLock
        conversations.addMessage(last.copy(id = UuidGenerator.generate(), createdAt = stamp()))
    }

    // ── Start and understanding ──

    private suspend fun startLocked(documentId: String) {
        val conversation = ensureConversation(documentId)
        val fill = fills.fillForDocument(documentId)
            ?: FormFill(
                id = fillId(documentId), documentId = documentId, status = FormFillStatus.UNDERSTANDING,
                conversationId = conversation, createdAt = clock(), updatedAt = clock(),
            ).also { fills.saveFill(it) }
        val fields = fills.fields(fill.id)
        when {
            fields.isEmpty() || fill.status == FormFillStatus.UNDERSTANDING -> understandForm(fill)
            fill.status == FormFillStatus.DONE || fill.status == FormFillStatus.STOPPED -> reopen(fill, fields)
            else -> reaskCurrent(fill)
        }
    }

    private suspend fun understandForm(fill: FormFill) {
        val conversation = ensureConversation(fill.documentId)
        val progressId = UuidGenerator.generate()
        suspend fun status(text: FormText, vararg args: String) = upsert(
            progressId, conversation, FormMessage(FormMessageKind.STATUS, text, args.toList()),
        )
        status(FormText.UNDERSTANDING, "0", STEPS)

        if (model.ensureLoaded() is PamResult.Error) return status(FormText.NO_MODEL)
        val pages = (documents.getDocumentPages(fill.documentId) as? PamResult.Success)?.data
            ?.sortedBy { it.pageNumber }?.map { it.ocrBlocks }.orEmpty()
        if (pages.isEmpty() || pages.all { it.isEmpty() }) return status(FormText.UNDERSTANDING_FAILED)

        val request = UnderstandFormRequest(
            documentId = fill.documentId, formFillId = fill.id, pages = pages, subjects = subjectCandidates(),
            today = today(), nowMs = clock(), fallbackLocale = fallbackLocale(),
        )
        val result = coroutineScope {
            val progress = Channel<FormProgress>(Channel.CONFLATED)
            val reporter = launch { for (p in progress) status(FormText.UNDERSTANDING, p.done.toString(), p.total.toString()) }
            try {
                understand(request) { progress.trySend(it) }
            } finally {
                progress.close()
                reporter.join()
            }
        }
        val understanding = when (result) {
            is PamResult.Error -> return status(FormText.UNDERSTANDING_FAILED)
            is PamResult.Success -> result.data
        }
        status(FormText.UNDERSTANDING, STEPS, STEPS)
        fills.saveFields(fill.id, understanding.fields)
        val next = fill.copy(
            localeTag = understanding.locale.toLanguageTag(), conversationId = conversation, updatedAt = clock(),
        )
        askSubject(next, understanding.subjectRanking, understanding.fields.size, pages.size)
    }

    /** A finished fill is opened again: the earlier skips are given another chance and the subject is asked once more. */
    private suspend fun reopen(fill: FormFill, fields: List<FormField>) {
        fields.filter { it.skipped }.forEach { fills.setSkipped(it.id, false, clock()) }
        askSubject(fill, emptyList(), fields.size, fields.maxOfOrNull { it.page } ?: 1)
    }

    // ── Who is it for ──

    private suspend fun askSubject(fill: FormFill, ranking: List<SubjectSuggestion>, fieldCount: Int, pageCount: Int) {
        val managed = managedProfiles()
        if (managed.isEmpty()) return resolveRoles(fill.copy(roleProfiles = emptyMap(), confirmedRoles = emptySet(), awaiting = null))
        val rank = ranking.map { it.profileId }
        val ordered = managed.sortedBy { p -> rank.indexOf(p.id).let { if (it < 0) Int.MAX_VALUE else it } }
        val best = ranking.firstOrNull()?.takeIf { it.plausible && it.reasonLine != null }
        val bestName = best?.let { b -> ordered.firstOrNull { it.id == b.profileId }?.name }
        val message = if (best != null && bestName != null) {
            FormMessage(
                FormMessageKind.QUESTION, FormText.FORM_FOUND_ASK_SUBJECT_REASON,
                listOf(fieldCount.toString(), pageCount.toString(), best.reasonLine!!, bestName), personChips(ordered, withNobody = true),
            )
        } else if (ranking.isNotEmpty() || fieldCount > 0) {
            FormMessage(
                FormMessageKind.QUESTION, FormText.FORM_FOUND_ASK_SUBJECT,
                listOf(fieldCount.toString(), pageCount.toString()), personChips(ordered, withNobody = true),
            )
        } else {
            FormMessage(FormMessageKind.QUESTION, FormText.ASK_SUBJECT, chips = personChips(ordered, withNobody = true))
        }
        fills.saveFill(
            fill.copy(status = FormFillStatus.ASK_SUBJECT, awaiting = FormAwaiting(FormAwaitKind.SUBJECT), currentFieldId = null, updatedAt = clock()),
        )
        post(fill.documentId, message)
    }

    private suspend fun reaskSubject(fill: FormFill) {
        val fields = fills.fields(fill.id)
        askSubject(fill, emptyList(), fields.size, fields.maxOfOrNull { it.page } ?: 1)
    }

    private suspend fun chooseSubject(fill: FormFill, personId: String?) {
        val before = fill.roleProfiles[FormRole.SUBJECT]
        if (before != personId) fills.clearValues(fill.id, clock())
        resolveRoles(
            fill.copy(
                roleProfiles = personId?.let { mapOf(FormRole.SUBJECT to it) }.orEmpty(),
                confirmedRoles = if (personId != null) setOf(FormRole.SUBJECT) else emptySet(),
                awaiting = null, roundAsked = 0,
            ),
        )
    }

    private suspend fun choosePerson(fill: FormFill, awaiting: FormAwaiting, personId: String?) {
        when (awaiting.kind) {
            FormAwaitKind.SUBJECT -> chooseSubject(fill, personId)
            FormAwaitKind.ROLE -> {
                val role = awaiting.role ?: return
                val withRole = if (personId != null) fill.withRole(role, personId, confirmed = true) else fill.withRole(role, NOBODY, confirmed = false)
                resolveRoles(withRole.copy(awaiting = null))
            }
            else -> Unit
        }
    }

    // ── Whose data, role by role ──

    /**
     * Settles the people behind the roles of the form's fields: the guardian of a child is "Me" and the partner (asked when that
     * is not exactly one person), the payer is the guardian (an adult pays for themselves), the signer the guardian of a child
     * or the subject. The subject and a chosen person are confirmed; a person worked out from the family is confirmed when the
     * user confirmed what it came from.
     */
    private suspend fun resolveRoles(start: FormFill) {
        var fill = start
        val fields = fills.fields(fill.id)
        val present = fields.mapNotNull { it.role }.toSet()
        val subjectId = fill.roleProfiles[FormRole.SUBJECT]
        if (subjectId != null) {
            val subject = findProfile(subjectId)
            val child = subject?.relationship == Relationship.CHILD
            val guardians = guardiansOf(subjectId)
            if (FormRole.GUARDIAN in present && FormRole.GUARDIAN !in fill.roleProfiles && child) {
                if (guardians.size == 1) {
                    fill = fill.withRole(FormRole.GUARDIAN, guardians.single().id, confirmed = true)
                } else {
                    return askRole(fill, FormRole.GUARDIAN, guardians.ifEmpty { managedProfiles().filter { it.id != subjectId } })
                }
            }
            if (FormRole.PAYER in present && FormRole.PAYER !in fill.roleProfiles) {
                val guardian = fill.roleProfiles[FormRole.GUARDIAN]?.takeIf { it != NOBODY }
                fill = when {
                    guardian != null -> fill.withRole(FormRole.PAYER, guardian, confirmed = FormRole.GUARDIAN in fill.confirmedRoles)
                    !child -> fill.withRole(FormRole.PAYER, subjectId, confirmed = true)
                    guardians.size == 1 -> fill.withRole(FormRole.PAYER, guardians.single().id, confirmed = true)
                    else -> return askRole(fill, FormRole.PAYER, guardians.ifEmpty { managedProfiles().filter { it.id != subjectId } })
                }
            }
            if (FormRole.SIGNER in present && FormRole.SIGNER !in fill.roleProfiles) {
                val signer = fill.roleProfiles[FormRole.GUARDIAN]?.takeIf { child && it != NOBODY } ?: subjectId
                fill = fill.withSigner(signer)
            }
        }
        fillAndAsk(fill)
    }

    private suspend fun askRole(fill: FormFill, role: FormRole, candidates: List<Profile>) {
        fills.saveFill(
            fill.copy(
                status = FormFillStatus.ASK_ROLE, awaiting = FormAwaiting(FormAwaitKind.ROLE, role = role), currentFieldId = null,
                updatedAt = clock(),
            ),
        )
        val text = if (role == FormRole.PAYER) FormText.ASK_PAYER else FormText.ASK_GUARDIAN
        post(fill.documentId, FormMessage(FormMessageKind.QUESTION, text, chips = personChips(candidates, withNobody = true)))
    }

    private fun FormFill.withRole(role: FormRole, personId: String, confirmed: Boolean): FormFill = copy(
        roleProfiles = roleProfiles + (role to personId),
        confirmedRoles = if (confirmed) confirmedRoles + role else confirmedRoles,
    )

    /** The signer is never a confirmed role: a signature holds no data, and the role gates nothing sensitive. */
    private fun FormFill.withSigner(signer: String): FormFill = copy(roleProfiles = roleProfiles + (FormRole.SIGNER to signer))

    // ── Fill from the profiles, then ask ──

    private suspend fun fillAndAsk(start: FormFill) {
        val fields = fills.fields(start.id)
        val context = FillContext(
            roleProfiles = start.roleProfiles.filterValues { it != NOBODY },
            confirmedRoles = start.confirmedRoles,
            locale = localeOf(start),
            todayPlaceProfileId = profiles.getProfiles().first().firstOrNull { it.isSelf }?.id,
            nowMs = clock(),
        )
        val result = fillValues.fill(fields, context)
        fills.saveFields(start.id, result.fields.map { it.copy(reconfirm = it.reconfirm || it.id in result.needsReconfirm) })
        val fill = start.copy(status = FormFillStatus.ASKING, awaiting = null, currentFieldId = null, roundAsked = 0, updatedAt = clock())
        fills.saveFill(fill)
        val progress = FillProgress.of(fills.fields(fill.id))
        post(
            fill.documentId,
            FormMessage(FormMessageKind.CARD, FormText.FILLED_INTRO, listOf(progress.ready.toString(), progress.total.toString()), fillId = fill.id),
        )
        askNext(fill)
    }

    // ── Asking, one field at a time ──

    private suspend fun askNext(start: FormFill) {
        val open = FillProgress.openFields(fills.fields(start.id))
        if (open.isEmpty()) return finish(start, byHand = false)
        if (start.roundAsked >= profile.roundSize) {
            fills.saveFill(start.copy(status = FormFillStatus.ASKING, awaiting = FormAwaiting(FormAwaitKind.CONTINUE), currentFieldId = null, updatedAt = clock()))
            return post(
                start.documentId,
                FormMessage(
                    FormMessageKind.QUESTION, FormText.MORE_QUESTIONS, listOf(open.size.toString()),
                    listOf(
                        FormChip(FormChipAction.CONTINUE, labelCode = FormChipLabel.CONTINUE),
                        FormChip(FormChipAction.BY_HAND, labelCode = FormChipLabel.BY_HAND),
                    ),
                ),
            )
        }
        askField(start, open.first())
    }

    private suspend fun askField(fill: FormFill, field: FormField, hint: FormText? = null) {
        val personId = personFor(fill, field)
        val chips = (answerChips.forField(field, personId) + FormChip(FormChipAction.SKIP, labelCode = FormChipLabel.SKIP))
            .map { it.copy(fieldId = field.id) }
        val awaiting = FormAwaiting(FormAwaitKind.ANSWER, fieldId = field.id, value = hint?.let { HINTED })
        fills.saveFill(fill.copy(status = FormFillStatus.ASKING, currentFieldId = field.id, awaiting = awaiting, updatedAt = clock()))
        if (hint != null) {
            return post(fill.documentId, FormMessage(FormMessageKind.QUESTION, hint, listOf(field.labelText), chips, fieldId = field.id))
        }
        if (field.reconfirm && field.value != null) {
            val shown = if (FormDataKeys.isSensitive(field.dataKey)) FormMask.of(field.value!!) else field.value!!
            return post(fill.documentId, FormMessage(FormMessageKind.QUESTION, FormText.STILL_RIGHT, listOf(field.labelText, shown), chips, fieldId = field.id))
        }
        val written = writer.write(field, personFor(fill, field)?.let { findProfile(it)?.name })
        if (written != null) {
            post(fill.documentId, FormMessage(FormMessageKind.QUESTION, null, listOf(field.labelText), chips, fieldId = field.id), content = written)
        } else {
            post(fill.documentId, FormMessage(FormMessageKind.QUESTION, templateFor(field), listOf(field.labelText), chips, fieldId = field.id))
        }
    }

    private fun templateFor(field: FormField): FormText = when {
        field.options.isNotEmpty() -> FormText.ASK_CHOICE
        field.kind == FormFieldKind.CHECKBOX -> FormText.ASK_YES_NO
        field.kind == FormFieldKind.DATE -> FormText.ASK_DATE
        else -> FormText.ASK_TEXT
    }

    private suspend fun reaskCurrent(fill: FormFill) {
        when (fill.awaiting?.kind) {
            FormAwaitKind.SUBJECT -> reaskSubject(fill)
            FormAwaitKind.ROLE -> {
                val role = fill.awaiting?.role ?: return
                val subjectId = fill.roleProfiles[FormRole.SUBJECT]
                askRole(fill, role, guardiansOf(subjectId.orEmpty()).ifEmpty { managedProfiles().filter { it.id != subjectId } })
            }
            else -> askNext(fill.copy(awaiting = null))
        }
    }

    // ── What the user said ──

    private class Typed(val text: String, val chip: Boolean = false)

    private suspend fun answerTyped(fill: FormFill, text: String) {
        val awaiting = fill.awaiting ?: return askNext(fill)
        when (awaiting.kind) {
            FormAwaitKind.ANSWER -> answerField(fill, awaiting, Typed(text))
            FormAwaitKind.SUBJECT, FormAwaitKind.ROLE -> {
                val choices = personChoices(fill, awaiting)
                val picked = interpreter.pickPerson(describeAwaiting(fill), text, choices)
                if (picked == null) reaskCurrent(fill) else choosePerson(fill, awaiting, picked)
            }
            FormAwaitKind.REMEMBER -> rememberAnswer(fill, awaiting, yes = interpreter.yesNo(describeAwaiting(fill), text) == true)
            FormAwaitKind.CONTINUE ->
                if (interpreter.yesNo(describeAwaiting(fill), text) == false) finish(fill, byHand = true) else continueRound(fill)
        }
    }

    private suspend fun answerField(fill: FormFill, awaiting: FormAwaiting, input: Typed) {
        val field = fieldOf(fill, awaiting) ?: return askNext(fill.copy(awaiting = null))
        val locale = localeOf(fill)
        val verified = if (input.chip) verifyChip(field, input.text, locale) else verifyTyped(field, input.text, locale)
        when (verified) {
            is Verification.Rejected -> {
                if (awaiting.value == HINTED) {
                    fills.setSkipped(field.id, true, clock())
                    post(fill.documentId, FormMessage(FormMessageKind.STATUS, FormText.LEFT_FOR_YOU, listOf(field.labelText)))
                    askNext(fill.copy(awaiting = null, roundAsked = fill.roundAsked + 1))
                } else {
                    askField(fill, field, hint = hintFor(verified.reason))
                }
            }
            is Verification.Accepted -> store(fill, field, verified.value, locale)
        }
    }

    /** A chip's value is one the conversation offered; it is checked like any answer, except that a tick box takes yes or no. */
    private suspend fun verifyChip(field: FormField, value: String, locale: Locale): Verification =
        if (field.reconfirm && value == field.value) Verification.Accepted(value) else verifyTyped(field, value, locale)

    private suspend fun verifyTyped(field: FormField, text: String, locale: Locale): Verification {
        val key = FormDataKeys.of(field.dataKey)
        if (field.options.isNotEmpty()) {
            val direct = AnswerVerifiers.verifyChoice(text, field.options)
            if (direct is Verification.Accepted) return direct
            val picked = interpreter.pickOption(field.labelText, text, field.options)
            return picked?.let { Verification.Accepted(field.options[it]) } ?: direct
        }
        if (field.kind == FormFieldKind.CHECKBOX) {
            if (text == CheckboxValue.YES || text == CheckboxValue.NO) return Verification.Accepted(text)
            val yes = interpreter.yesNo(field.labelText, text) ?: return Verification.Rejected(Rejection.NOT_AN_OPTION)
            return Verification.Accepted(if (yes) CheckboxValue.YES else CheckboxValue.NO)
        }
        val kind = key?.valueKind ?: if (field.kind == FormFieldKind.DATE) FormValueKind.DATE else FormValueKind.TEXT
        return AnswerVerifiers.verify(kind, text, locale, today = today())
    }

    /** Writes a verified answer, then offers to remember it for the person. */
    private suspend fun store(fill: FormFill, field: FormField, normalized: String, locale: Locale) {
        val key = FormDataKeys.of(field.dataKey)
        val shown = when (key?.valueKind) {
            FormValueKind.DATE -> FormValueFormatter.date(normalized, locale)
            FormValueKind.IBAN -> FormValueFormatter.iban(normalized)
            else -> if (field.kind == FormFieldKind.DATE && key == null) FormValueFormatter.date(normalized, locale) else normalized
        }
        val personId = personFor(fill, field)
        val next = fill.copy(roundAsked = fill.roundAsked + 1, awaiting = null)
        if (field.reconfirm && shown == field.value) {
            fills.setValue(field.id, shown, field.valueSource, ReviewState.CONFIRMED, field.profileId, clock())
            return askNext(next)
        }
        fills.setValue(field.id, shown, FormValueSource.USER, ReviewState.EDITED, personId, clock())
        val person = personId?.let { findProfile(it) }
        if (key == null || person == null || key.id in NOT_PERSONAL || (field.kind == FormFieldKind.CHECKBOX && field.options.isEmpty())) {
            return askNext(next)
        }
        if (people.valueOf(person.id, key.id)?.value == normalized) return askNext(next)
        fills.saveFill(
            next.copy(awaiting = FormAwaiting(FormAwaitKind.REMEMBER, fieldId = field.id, value = normalized), currentFieldId = field.id, updatedAt = clock()),
        )
        post(
            fill.documentId,
            FormMessage(
                FormMessageKind.QUESTION, FormText.REMEMBER, listOf(person.name),
                listOf(
                    FormChip(FormChipAction.REMEMBER_YES, labelCode = FormChipLabel.YES),
                    FormChip(FormChipAction.REMEMBER_NO, labelCode = FormChipLabel.NO),
                ),
                fieldId = field.id,
            ),
        )
    }

    private suspend fun rememberAnswer(fill: FormFill, awaiting: FormAwaiting, yes: Boolean) {
        val field = fieldOf(fill, awaiting)
        val personId = field?.let { personFor(fill, it) }
        val key = field?.dataKey
        val value = awaiting.value
        val next = fill.copy(awaiting = null)
        if (yes && personId != null && key != null && value != null) {
            val person = findProfile(personId)
            val saved = remember(personId, key, value, FactSource.FORM_ANSWER, fill.documentId)
            val message = if (saved is PamResult.Success) {
                FormMessage(FormMessageKind.STATUS, FormText.REMEMBERED, listOfNotNull(person?.name))
            } else {
                FormMessage(FormMessageKind.STATUS, FormText.REMEMBER_FAILED, listOfNotNull(person?.name))
            }
            post(fill.documentId, message)
        }
        askNext(next)
    }

    private suspend fun skipOpenQuestion(fill: FormFill) {
        val awaiting = fill.awaiting
        when (awaiting?.kind) {
            FormAwaitKind.ANSWER -> {
                awaiting.fieldId?.let { fills.setSkipped(it, true, clock()) }
                askNext(fill.copy(awaiting = null, roundAsked = fill.roundAsked + 1))
            }
            FormAwaitKind.REMEMBER -> rememberAnswer(fill, awaiting, yes = false)
            FormAwaitKind.CONTINUE -> continueRound(fill)
            else -> reaskCurrent(fill)
        }
    }

    private suspend fun continueRound(fill: FormFill) = askNext(fill.copy(awaiting = null, roundAsked = 0))

    private suspend fun changeSubject(fill: FormFill, text: String) {
        val picked = interpreter.pickPerson(SUBJECT_QUESTION, text, subjectChoices())
        if (picked != null) chooseSubject(fill, picked) else reaskSubject(fill)
    }

    private suspend fun changeValue(fill: FormFill, text: String) {
        val fields = fills.fields(fill.id).filter { it.kind != FormFieldKind.SIGNATURE }
        val target = interpreter.pickField(text, fields)?.let { id -> fields.firstOrNull { it.id == id } }
            ?: fill.awaiting?.fieldId?.let { id -> fields.firstOrNull { it.id == id } }
        if (target == null) reaskCurrent(fill) else askField(fill, target)
    }

    private suspend fun stop(fill: FormFill) {
        fills.saveFill(fill.copy(status = FormFillStatus.STOPPED, awaiting = null, currentFieldId = null, updatedAt = clock()))
        post(fill.documentId, FormMessage(FormMessageKind.STATUS, FormText.STOPPED))
    }

    /** DONE: the card says where it all stands. With [byHand] the questions still open are left for the user first. */
    private suspend fun finish(fill: FormFill, byHand: Boolean) {
        if (byHand) {
            val open = FillProgress.openFields(fills.fields(fill.id))
            open.forEach { fills.setSkipped(it.id, true, clock()) }
            post(fill.documentId, FormMessage(FormMessageKind.STATUS, FormText.BY_HAND, listOf(open.size.toString())))
        }
        fills.saveFill(fill.copy(status = FormFillStatus.DONE, awaiting = null, currentFieldId = null, updatedAt = clock()))
        val p = FillProgress.of(fills.fields(fill.id))
        post(
            fill.documentId,
            FormMessage(
                FormMessageKind.CARD, FormText.ALL_SET,
                listOf(p.ready.toString(), p.total.toString(), p.signatures.toString(), p.firstSignaturePage?.toString().orEmpty()),
                fillId = fill.id,
            ),
        )
    }

    // ── Helpers ──

    private suspend fun describeAwaiting(fill: FormFill): String = when (fill.awaiting?.kind) {
        FormAwaitKind.ANSWER -> "The assistant asked for the form field «${fill.awaiting?.let { fieldOf(fill, it)?.labelText }.orEmpty()}»."
        FormAwaitKind.SUBJECT -> SUBJECT_QUESTION
        FormAwaitKind.ROLE -> "The assistant asked which person is the ${fill.awaiting?.role?.name?.lowercase()?.replace('_', ' ')} of the form."
        FormAwaitKind.REMEMBER -> "The assistant asked whether to remember the answer for the person (yes or no)."
        FormAwaitKind.CONTINUE -> "The assistant asked whether to continue with the remaining questions or leave the rest to the user."
        null -> "The assistant is filling in a form."
    }

    private suspend fun fieldOf(fill: FormFill, awaiting: FormAwaiting): FormField? {
        val id = awaiting.fieldId ?: return null
        return fills.fields(fill.id).firstOrNull { it.id == id }
    }

    /** The person behind a field's role, or null when none answers for it. */
    private fun personFor(fill: FormFill, field: FormField): String? =
        field.role?.let { fill.roleProfiles[it] }?.takeIf { it != NOBODY }

    private suspend fun personChoices(fill: FormFill, awaiting: FormAwaiting): List<PersonChoice> {
        val subjectId = fill.roleProfiles[FormRole.SUBJECT]
        val candidates = when (awaiting.kind) {
            FormAwaitKind.ROLE -> guardiansOf(subjectId.orEmpty()).ifEmpty { managedProfiles().filter { it.id != subjectId } }
            else -> managedProfiles()
        }
        return candidates.map(::choiceOf)
    }

    private suspend fun subjectChoices(): List<PersonChoice> = managedProfiles().map(::choiceOf)

    private fun choiceOf(p: Profile): PersonChoice {
        val relation = when {
            p.isSelf -> "the user"
            else -> RELATIONS[p.relationship] ?: "a person"
        }
        return PersonChoice(p.id, p.name, "$relation ${p.name}")
    }

    private fun personChips(profiles: List<Profile>, withNobody: Boolean): List<FormChip> =
        profiles.map { FormChip(FormChipAction.PERSON, label = it.name, arg = it.id) } +
            if (withNobody) listOf(FormChip(FormChipAction.PERSON, labelCode = FormChipLabel.SOMEONE_ELSE, arg = "")) else emptyList()

    private suspend fun managedProfiles(): List<Profile> =
        profiles.getProfiles().first().filter { it.isManaged }.sortedByDescending { it.isSelf }

    private suspend fun findProfile(id: String): Profile? = profiles.getProfiles().first().firstOrNull { it.id == id }

    private suspend fun subjectCandidates(): List<SubjectCandidate> = managedProfiles().map {
        SubjectCandidate(it.id, it.name, it.relationship, it.isSelf, it.birthDate?.let(::parseDate))
    }

    private fun parseDate(iso: String): LocalDate? = try {
        LocalDate.parse(iso)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun localeOf(fill: FormFill): Locale = fill.localeTag?.let(Locale::forLanguageTag) ?: fallbackLocale()

    private suspend fun documentIsForm(documentId: String): Boolean =
        (documents.getDocumentById(documentId) as? PamResult.Success)?.data?.extractionType == ExtractionSchema.FORM_APPLICATION.id

    private fun hintFor(reason: Rejection): FormText = when (reason) {
        Rejection.EMPTY -> FormText.HINT_EMPTY
        Rejection.NOT_AN_OPTION, Rejection.AMBIGUOUS_OPTION -> FormText.HINT_NOT_AN_OPTION
        Rejection.NOT_A_DATE -> FormText.HINT_NOT_A_DATE
        Rejection.NOT_A_PHONE -> FormText.HINT_NOT_A_PHONE
        Rejection.NOT_AN_EMAIL -> FormText.HINT_NOT_AN_EMAIL
        Rejection.NOT_AN_IBAN, Rejection.BAD_IBAN_CHECKSUM -> FormText.HINT_NOT_AN_IBAN
        Rejection.NOT_A_POSTCODE -> FormText.HINT_NOT_A_POSTCODE
    }

    // ── Messages ──


    /** A strictly increasing time, so messages written in the same millisecond keep their order. */
    private fun stamp(): Long {
        lastStamp = maxOf(clock(), lastStamp + 1)
        return lastStamp
    }

    private suspend fun ensureConversation(documentId: String): String {
        val id = conversationId(documentId)
        if (conversations.getConversationById(id) is PamResult.Error) {
            val title = (documents.getDocumentById(documentId) as? PamResult.Success)?.data?.title.orEmpty()
            conversations.createConversation(
                AiConversation(
                    id = id, documentId = documentId, aiModelId = null, modelType = AiModelType.LOCAL, title = title,
                    lastMessageAt = clock(), createdAt = clock(),
                ),
            )
        }
        return id
    }

    private suspend fun userSaid(documentId: String, text: String) {
        val id = ensureConversation(documentId)
        conversations.addMessage(
            AiMessage(id = UuidGenerator.generate(), conversationId = id, role = MessageRole.USER, content = text, createdAt = stamp()),
        )
    }

    private suspend fun post(documentId: String, form: FormMessage, content: String = "") {
        val id = ensureConversation(documentId)
        conversations.addMessage(FormMessageCodec.toMessage(UuidGenerator.generate(), id, stamp(), form, content))
    }

    /** The time each progress line was first written, so an update keeps its place in the transcript. */
    private val progressStamps = HashMap<String, Long>()

    /** Adds the message [id] the first time and updates it in place afterwards (a progress line). */
    private suspend fun upsert(id: String, conversationId: String, form: FormMessage) {
        val first = id !in progressStamps
        val at = progressStamps.getOrPut(id) { stamp() }
        val message = FormMessageCodec.toMessage(id, conversationId, at, form)
        if (first) conversations.addMessage(message) else conversations.updateMessage(message)
    }

    companion object {
        /** A document's own chat (one conversation per document, see the chat screen). */
        fun conversationId(documentId: String) = "conv-$documentId"

        /** The one fill of a document. */
        fun fillId(documentId: String) = "fill-$documentId"

        /** The understanding has this many steps (see [com.postsaimanager.core.domain.form.FormStep]). */
        private const val STEPS = "5"

        /** A role the user declined to name: decided, so it is not asked again, and no profile answers for it. */
        private const val NOBODY = ""

        /** The open question was already re-asked once with a hint. */
        private const val HINTED = "hinted"

        private const val SUBJECT_QUESTION = "The assistant asked who the form is for."

        private val ACTIVE = setOf(FormFillStatus.ASK_SUBJECT, FormFillStatus.ASK_ROLE, FormFillStatus.ASKING)

        /** Keys that are not a detail of a person: nothing to remember. */
        private val NOT_PERSONAL = setOf(FormDataKeys.TODAY_DATE.id, FormDataKeys.TODAY_PLACE.id, FormDataKeys.SIGNATURE.id)

        /** How the model is told the relationship (English content descriptions, never shown to users). */
        private val RELATIONS: Map<Relationship?, String> = mapOf(
            Relationship.CHILD to "the user's child",
            Relationship.PARTNER to "the user's partner",
            Relationship.PARENT to "the user's parent",
            Relationship.RELATIVE to "a relative of the user",
            Relationship.FRIEND to "a friend of the user",
            Relationship.OTHER to "a person",
        )
    }
}
