package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.domain.agent.AgentLoop
import com.postsaimanager.core.domain.agent.AgentModel
import com.postsaimanager.core.domain.agent.AgentOutcome
import com.postsaimanager.core.domain.agent.AgentTrace
import com.postsaimanager.core.domain.form.fill.FormMessageCodec
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import com.postsaimanager.core.domain.form.fill.FillRequestDetector
import com.postsaimanager.core.domain.form.fill.FormFillTrace
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.FormFillRepository
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the form agent did with a message the user typed in a document chat. */
enum class FormRoute {
    /** Nothing to do with a form fill: the normal chat answers it. */
    NOT_FOR_FORM,

    /** The agent took it (and stored it as the user's message). */
    HANDLED,
}

/**
 * The form-filling conversation of a document's AI chat, run by the tool-calling agent ([AgentLoop]) with the form tools. The AI
 * decides every step (who the form is for, what to ask and how, how to read a reply, when to remember, when it is done); this
 * class only starts a run, hands the user's messages to the loop, and says what went wrong in plain status lines.
 *
 * - **Start**: the card's "Help me fill it" or a typed request (recognised by [FillRequestDetector], a model score) begins a run:
 *   the beta-notice line marks its start, the agent reads the form and takes it from there.
 * - **Reply**: while a run is going, every typed message and every tapped chip is the user's next message to the agent.
 * - **Stop / leave**: the run pauses (what it did is stored); a chip, or the next open of the chat after a crash, continues it from
 *   the stored steps.
 *
 * One run at a time (a lock serialises everything). The fill's stored state (`form_fills`, `form_fields`) is the one owner of
 * the form's values; the agent's conversation is the stored tool calls and results.
 */
class FormFillAgent(
    private val fills: FormFillRepository,
    private val documents: DocumentRepository,
    private val log: FormChatLog,
    private val tools: FormAgentTools,
    private val model: AgentModel,
    private val detector: FillRequestDetector,
    private val activeModels: ActiveModelProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val trace: FormFillTrace = FormFillTrace.NONE,
    private val agentTrace: AgentTrace = AgentTrace.NONE,
) {

    private val lock = Mutex()

    /** "Help me fill it": starts a run on the document, or picks the stored one up where it stands. */
    suspend fun start(documentId: String) = lock.withLock {
        val fill = fills.fillForDocument(documentId)
        when {
            // Nothing of this agent version is stored (a first fill, or the transcript of an older version): a fresh run. What the
            // chat shows above stays as history; the model never sees it.
            fill == null || !hasRun(documentId) -> {
                beginRun(documentId)
                run(documentId)
            }
            // A finished or stopped run is not silently restarted or continued: the user chooses.
            fill.status == FormFillStatus.DONE -> offer(documentId, FormText.AGENT_DONE_OFFER, listOf(startOverChip()))
            fill.status == FormFillStatus.STOPPED -> offer(documentId, FormText.AGENT_RESUME_OFFER, listOf(continueChip(), startOverChip()))
            else -> run(documentId)
        }
    }

    /**
     * The chat was opened: a run that was cut off (the user left, the process died) goes on from its stored steps. One that waits
     * for the user, was stopped on purpose or is done is left alone, so opening a chat never costs a model call.
     */
    suspend fun resume(documentId: String) = lock.withLock {
        val fill = fills.fillForDocument(documentId) ?: return@withLock
        if (fill.status != FormFillStatus.UNDERSTANDING && fill.status != FormFillStatus.ASKING) return@withLock
        if (!hasRun(documentId) || isWaiting(documentId)) return@withLock
        run(documentId)
    }

    /** A message the user typed in the document chat. */
    suspend fun route(documentId: String, text: String): FormRoute = lock.withLock {
        val fill = fills.fillForDocument(documentId)
        val active = fill != null && (fill.status == FormFillStatus.UNDERSTANDING || fill.status == FormFillStatus.ASKING) && hasRun(documentId)
        if (active) {
            log.userSaid(documentId, text)
            run(documentId)
            return@withLock FormRoute.HANDLED
        }
        // A run that stopped or failed and was not finished: what the user types is not silently sent to the plain chat. It is kept, and
        // the user is told the fill is paused, with Continue / Start over.
        if (fill != null && fill.status != FormFillStatus.DONE && hasRun(documentId)) {
            log.userSaid(documentId, text)
            log.post(documentId, FormMessage(FormMessageKind.STATUS, FormText.AGENT_PAUSED, chips = listOf(continueChip(), startOverChip())))
            return@withLock FormRoute.HANDLED
        }
        if (!detector.asksForFill(text, documentIsForm(documentId))) return@withLock FormRoute.NOT_FOR_FORM
        beginRun(documentId)
        log.userSaid(documentId, text)
        run(documentId)
        FormRoute.HANDLED
    }

    /** The user tapped [chip]; [shownText] is its label as the user saw it, sent to the agent as the user's message. */
    suspend fun chip(documentId: String, chip: FormChip, shownText: String) = lock.withLock {
        trace.event("chip", "doc=$documentId action=${chip.action}")
        when (chip.action) {
            FormChipAction.ANSWER -> if (hasRun(documentId)) {
                log.userSaid(documentId, shownText)
                run(documentId)
            }
            // An earlier Continue chip (the run went on, or was started over since) does nothing: only a stopped run is continued.
            FormChipAction.CONTINUE ->
                if (hasRun(documentId) && fills.fillForDocument(documentId)?.status == FormFillStatus.STOPPED) run(documentId)
            FormChipAction.START_OVER -> {
                val fill = fills.fillForDocument(documentId)
                if (fill != null) {
                    fills.clearValues(fill.id, clock())
                    fills.saveFill(fill.copy(roleProfiles = emptyMap(), confirmedRoles = emptySet(), awaiting = null, currentFieldId = null))
                }
                // The engine still holds the old run's chat under the same conversation id: drop it, so the new run starts a fresh session
                // (a fresh history) instead of the cached one that remembers the answers just forgotten.
                model.resetSession()
                beginRun(documentId)
                run(documentId)
            }
            FormChipAction.OPEN_MODELS -> Unit // opens a screen: the UI handles it, it is never an answer
        }
    }

    // ── A run ──

    private suspend fun beginRun(documentId: String) {
        val conversation = log.ensureConversation(documentId)
        val now = clock()
        val fill = fills.fillForDocument(documentId)?.copy(awaiting = null, currentFieldId = null)
            ?: FormFill(
                id = FormChatLog.fillId(documentId), documentId = documentId, status = FormFillStatus.ASKING,
                conversationId = conversation, createdAt = now, updatedAt = now,
            )
        fills.saveFill(fill.copy(status = FormFillStatus.ASKING, updatedAt = now))
        // The agent version marks the run as this agent's: an older run (or the earlier code-driven chat) is never carried on.
        log.post(documentId, FormMessage(FormMessageKind.STATUS, FormText.BETA_NOTICE, args = listOf(FormAgentSpec.VERSION)))
        trace.event("begin", "fill=${fill.id} version=${FormAgentSpec.VERSION}")
    }

    /** Says what the user can do with a fill that already exists (unless the chat already ends with such an offer). */
    private suspend fun offer(documentId: String, text: FormText, chips: List<FormChip>) {
        val last = log.messages(documentId).lastOrNull()?.let(FormMessageCodec::parse)
        if (last != null && last.kind == FormMessageKind.STATUS && last.chips.any { it.action == FormChipAction.START_OVER }) return
        log.post(documentId, FormMessage(FormMessageKind.STATUS, text, chips = chips))
    }

    private fun continueChip() = FormChip(FormChipAction.CONTINUE, labelCode = FormChipLabel.CONTINUE)

    private fun startOverChip() = FormChip(FormChipAction.START_OVER, labelCode = FormChipLabel.START_OVER)

    private suspend fun run(documentId: String) {
        if (fills.fillForDocument(documentId) == null) return
        if (model.ensureLoaded() is PamResult.Error) return log.post(documentId, FormMessage(FormMessageKind.STATUS, FormText.NO_MODEL))
        setStatus(documentId, FormFillStatus.ASKING)
        // The profile of the model the run really uses (the form model, see ActiveModelProvider.formModelId), not the chat model's.
        val modelProfile = ModelProfiles.of(activeModels.formModelId())
        val agent = modelProfile.agent.copy(contextTokens = modelProfile.contextTokens)
        val loop = AgentLoop(model, agent.format.create(), agent, trace = agentTrace)
        val outcome = try {
            loop.run(tools.specFor(documentId), FormAgentTranscript(documentId, log))
        } catch (e: CancellationException) {
            // Stop, or the chat was left: the run pauses (what it did is stored) until the user continues it.
            withContext(NonCancellable) { pause(documentId, FormText.AGENT_PAUSED) }
            throw e
        }
        trace.event("outcome", "doc=$documentId ${outcome::class.simpleName}")
        when (outcome) {
            is AgentOutcome.StepLimit -> pause(documentId, FormText.AGENT_STUCK)
            is AgentOutcome.Failed -> pause(documentId, FormText.AGENT_FAILED)
            is AgentOutcome.EndedTurn, is AgentOutcome.Waiting, AgentOutcome.Idle -> Unit
        }
    }

    private suspend fun pause(documentId: String, text: FormText) {
        setStatus(documentId, FormFillStatus.STOPPED)
        log.post(
            documentId,
            FormMessage(FormMessageKind.STATUS, text, chips = listOf(continueChip(), startOverChip())),
        )
    }

    private suspend fun setStatus(documentId: String, status: FormFillStatus) {
        val fill = fills.fillForDocument(documentId) ?: return
        if (fill.status != status && fill.status != FormFillStatus.DONE) fills.saveFill(fill.copy(status = status, updatedAt = clock()))
    }

    /** Whether the document's newest run is one of this agent version (an older run, or none, is not one to carry on). */
    private suspend fun hasRun(documentId: String): Boolean = FormAgentTranscript.hasCurrentRun(log.messages(documentId))

    /** Whether the run already handed the conversation to the user (its last stored step is a turn-ending call). */
    private suspend fun isWaiting(documentId: String): Boolean {
        val last = FormAgentTranscript(documentId, log).entries().lastOrNull() ?: return true
        return last is AgentEntry.Call && tools.specFor(documentId).tools[last.name]?.endsTurn == true
    }

    private suspend fun documentIsForm(documentId: String): Boolean =
        (documents.getDocumentById(documentId) as? PamResult.Success)?.data?.extractionType == ExtractionSchema.FORM_APPLICATION.id
}
