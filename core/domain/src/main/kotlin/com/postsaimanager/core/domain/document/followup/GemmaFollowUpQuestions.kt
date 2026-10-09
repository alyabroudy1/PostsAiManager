package com.postsaimanager.core.domain.document.followup

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.FollowUpRequest
import com.postsaimanager.core.domain.ai.ModelUse
import com.postsaimanager.core.domain.ai.loadForUse
import com.postsaimanager.core.domain.ai.StructuredRequest
import com.postsaimanager.core.domain.document.contacts.SameContactDecision
import com.postsaimanager.core.domain.document.contacts.SameContactProfile
import com.postsaimanager.core.domain.document.contacts.SameContactQuestion
import com.postsaimanager.core.domain.document.list.PartyNames
import com.postsaimanager.core.domain.document.people.ConcernedPeopleProfile
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.form.SuggestSubject
import com.postsaimanager.core.domain.organisation.DetailOwner
import com.postsaimanager.core.domain.organisation.DetailOwnerProfile
import com.postsaimanager.core.domain.organisation.DetailQuestion
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.timeline.SameMatterDecision
import com.postsaimanager.core.domain.timeline.SameMatterProfile
import com.postsaimanager.core.domain.timeline.SameMatterQuestion
import com.postsaimanager.core.model.ModelRuntime
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * [FollowUpQuestions] for the Gemma reader: each question is one more constrained turn in the conversation that read the letter
 * ([StructuredRequest.keepOpenAs], keyed by the document's id), so the letter and its pictures are not read again and the question costs
 * a second or two. The answer is a choice among the candidates' ids, a made-up candidate and "none" ([FollowUpPrompts]); the code only
 * verifies that it is one of the options offered.
 *
 * When that conversation is not there (the app was restarted since the reading, an old reading, the batch backfill of the people
 * check, or something else used the model in between) a fresh conversation is opened with the letter's stored text and no picture, and
 * kept open for the rest of the questions about that letter, until [finish]. It is background work: it never queues behind a chat
 * reply or a reading (the engine skips it when it is busy), and it loads nothing while another caller holds the model.
 *
 * No answer (no Gemma model, the model busy, no stored text, an unusable answer) is an error, never "no match": the callers leave
 * the decision pending and it is asked again on the next reading.
 */
class GemmaFollowUpQuestions @Inject constructor(
    private val engine: ChatEngine,
    private val activeModel: ActiveModelProvider,
    private val documents: DocumentRepository,
    private val peopleProfile: ConcernedPeopleProfile,
    private val contactProfile: SameContactProfile,
    private val detailProfile: DetailOwnerProfile,
    private val matterProfile: SameMatterProfile,
    private val log: AfterReadingLog = AfterReadingLog.SILENT,
) : FollowUpQuestions {

    override suspend fun concernedPeople(documentId: String, letter: String, members: List<SubjectCandidate>, read: ReadParties): PamResult<Set<String>> {
        val listed = members.take(peopleProfile.maxMembers)
        if (listed.isEmpty()) return PamResult.Success(emptySet())
        // The members whose whole name the letter prints: shown to the model as evidence only; the choice stays the model's.
        val printed = listed.filter { PartyNames.printsFullName(letter, it.name) }.map { it.profileId }.toSet()
        // One yes/no question per person, the model deciding each; then the same question for a made-up person as the calibration.
        suspend fun asks(name: String, relation: String?, printedInFull: Boolean): PamResult<Boolean> {
            val ask = FollowUpPrompts.concernedPerson(name, relation, printedInFull, read)
            val json = when (val answer = converse(documentId, ask) { letter.take(peopleProfile.maxLetterChars) }) {
                is PamResult.Error -> return answer
                is PamResult.Success -> answer.data
            }
            val chosen = FollowUpPrompts.choice(json, ask) ?: return unusable()
            // Debug: each question and its answer (invented letters only).
            log.answered(documentId, "concerned people ask", "prompt=${ask.prompt.replace('\n', '|')} answer=$chosen")
            return PamResult.Success(chosen == FollowUpPrompts.YES)
        }
        val yes = LinkedHashSet<String>()
        for (member in listed) {
            when (val a = asks(member.name, SuggestSubject.relation(member), member.profileId in printed)) {
                is PamResult.Error -> return a
                is PamResult.Success -> if (a.data) yes += member.profileId
            }
        }
        val decoyYes = when (val a = asks(peopleProfile.baselineName, null, false)) {
            is PamResult.Error -> return a
            is PamResult.Success -> a.data
        }
        // A model that says yes for somebody who cannot be in the letter is not answering reliably: nobody is kept.
        val ids = if (decoyYes) emptySet() else yes
        log.answered(documentId, "concerned people", "offered=${listed.size} printedInFull=${printed.size} yes=${yes.size} madeUpAnsweredYes=$decoyYes kept=${ids.size}")
        return PamResult.Success(ids)
    }

    override suspend fun sameContact(documentId: String, question: SameContactQuestion): PamResult<SameContactDecision> {
        val listed = question.candidates.take(contactProfile.maxCandidates)
        val ask = FollowUpPrompts.sameContact(question.copy(candidates = listed), contactProfile.baselineName)
        val json = when (val answer = converse(documentId, ask)) {
            is PamResult.Error -> return answer
            is PamResult.Success -> answer.data
        }
        val chosen = FollowUpPrompts.choice(json, ask) ?: return unusable()
        val matched = FollowUpPrompts.matched(chosen, ask)?.let { listed[ask.candidateIds.indexOf(it)].id }
        return PamResult.Success(SameContactDecision(matchedId = matched, asked = emptyList(), baseline = null, margin = 0.0))
    }

    override suspend fun detailOwner(documentId: String, question: DetailQuestion): PamResult<DetailOwner> {
        val ask = FollowUpPrompts.detailOwner(question, detailProfile.baselineContact)
        val json = when (val answer = converse(documentId, ask)) {
            is PamResult.Error -> return answer
            is PamResult.Success -> answer.data
        }
        val chosen = FollowUpPrompts.choice(json, ask) ?: return unusable()
        return PamResult.Success(
            when (FollowUpPrompts.matched(chosen, ask)) {
                FollowUpPrompts.CONTACT -> DetailOwner.CONTACT
                FollowUpPrompts.ORGANISATION -> DetailOwner.ORGANISATION
                else -> DetailOwner.NEITHER
            },
        )
    }

    override suspend fun sameMatter(documentId: String, question: SameMatterQuestion): PamResult<SameMatterDecision> {
        val listed = question.candidates.take(matterProfile.maxCandidates)
        val ask = FollowUpPrompts.sameMatter(question.copy(candidates = listed), matterProfile.baselineTitle, matterProfile.maxEventsPerMatter)
        val json = when (val answer = converse(documentId, ask)) {
            is PamResult.Error -> return answer
            is PamResult.Success -> answer.data
        }
        val chosen = FollowUpPrompts.choice(json, ask) ?: return unusable()
        val matched = FollowUpPrompts.matched(chosen, ask)?.let { listed[ask.candidateIds.indexOf(it)].id }
        // Option ids only (never a word of the letter): what was offered and chosen, so a letter left out of its matter can be traced.
        log.answered(documentId, "same matter", "offered=${listed.size} chose=$chosen joined=${matched != null}")
        return PamResult.Success(SameMatterDecision(matchedId = matched, asked = emptyList(), baseline = null, margin = 0.0))
    }

    override suspend fun finish(documentId: String) {
        engine.closeStructured(documentId)
    }

    /**
     * Asks [ask] in the conversation kept open for [documentId], or in a fresh one with the letter's text. [letter] is the letter's text
     * when the caller has it already (the people check); otherwise the stored text of the pages is read when it is needed.
     */
    private suspend fun converse(documentId: String, ask: FollowUpAsk, letter: (suspend () -> String)? = null): PamResult<String> {
        val inReader = withTimeoutOrNull(TIMEOUT_MS + GRACE_MS) {
            engine.continueStructured(FollowUpRequest(documentId, ask.prompt, ask.schema, TIMEOUT_MS))
        }
        if (inReader != null) return PamResult.Success(inReader)
        return inFreshConversation(documentId, ask, letter)
    }

    /** The fallback: a conversation of its own with the letter's text only (no picture), kept open under the document's id. */
    private suspend fun inFreshConversation(documentId: String, ask: FollowUpAsk, letter: (suspend () -> String)?): PamResult<String> {
        // Background work gives way: with a reply or a reading holding the model there is nothing to wait for, the question stays pending.
        if (engine.isBusy) return unavailable("the model is busy")
        val path = activeModel.activeModelPath() ?: return unavailable("no chat model is installed")
        // The reading's own config (the accelerator the reader runs on), so the questions never cost a reload; the load goes through the
        // engine's chat-priority policy (it waits for a running call, never cuts one).
        val config = activeModel.readingModelConfig()
        if (config.runtime != ModelRuntime.LITERT_LM) return unavailable("the chat model is not a LiteRT-LM model")
        val text = (letter?.invoke() ?: storedText(documentId)).trim()
        if (text.isEmpty()) return unavailable("the letter has no stored text")
        if (engine.loadForUse(ModelUse.READING, path, config) is PamResult.Error) return unavailable("the chat model could not be loaded")
        val request = StructuredRequest(
            system = SYSTEM,
            prompt = "LETTER\n${text.take(MAX_LETTER_CHARS)}\n\n${ask.prompt}",
            schema = ask.schema,
            maxTokens = ANSWER_TOKENS,
            timeoutMs = TIMEOUT_MS,
            keepOpenAs = documentId,
        )
        val json = withTimeoutOrNull(TIMEOUT_MS + GRACE_MS) { engine.generateStructured(request) }
            ?: return unavailable("no answer (the model is busy, the run failed or it took too long)")
        return PamResult.Success(json)
    }

    private suspend fun storedText(documentId: String): String =
        (documents.getDocumentPages(documentId) as? PamResult.Success)?.data.orEmpty()
            .sortedBy { it.pageNumber }.mapNotNull { it.ocrText }.joinToString("\n")

    private fun unavailable(reason: String): PamResult.Error = PamResult.Error(PamError.ExtractionFailed(detail = "Follow-up not answered: $reason"))

    private fun unusable(): PamResult.Error = PamResult.Error(PamError.ExtractionFailed(detail = "The follow-up answer could not be used"))

    private companion object {
        const val SYSTEM = "You read a letter and answer questions about the people and matters it names. Answer with JSON only. " +
            "Choose only from the options given, and answer none when no option fits: never guess."
        const val TIMEOUT_MS = 60_000L
        const val GRACE_MS = 10_000L

        /** One id, or a few: the answer is a handful of tokens. */
        const val ANSWER_TOKENS = 128
        const val MAX_LETTER_CHARS = 6_000
    }
}
