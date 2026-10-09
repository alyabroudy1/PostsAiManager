package com.postsaimanager.core.domain.document.followup

import com.postsaimanager.core.domain.document.contacts.ContactFacts
import com.postsaimanager.core.domain.document.contacts.SameContactQuestion
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.form.SuggestSubject
import com.postsaimanager.core.domain.organisation.DetailKind
import com.postsaimanager.core.domain.organisation.DetailQuestion
import com.postsaimanager.core.domain.timeline.SameMatterQuestion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * One follow-up question as it is sent: the [prompt] (what the model reads as the next turn), the [schema] its answer is constrained to,
 * and the option ids the answer may contain. [candidateIds] are the options that are real candidates, in the order they were offered
 * (the ids of the candidates, opaque to the model); the made-up [FollowUpPrompts.DECOY] and [FollowUpPrompts.NONE] are options too.
 */
class FollowUpAsk(
    val prompt: String,
    val schema: String,
    val candidateIds: List<String>,
    val multiple: Boolean,
) {
    val options: Set<String> get() = candidateIds.toSet() + FollowUpPrompts.DECOY + FollowUpPrompts.NONE
}

/**
 * The follow-up questions as text, and what their constrained answers mean. Pure: nothing here reaches a model.
 *
 * The answer to each question is a choice among the ids of the candidates, a made-up candidate ([DECOY], a name that cannot be in a
 * letter) and [NONE]. The made-up candidate takes the place of the old baseline margin: the Qwen scorer called a candidate a match
 * only when its yes/no score beat a made-up party's by a margin, because a small model leans Yes on everything. A constrained choice
 * cannot lean "yes to everything", but it can still pick something to please, so the made-up option is offered all the same: a model
 * that picks it (or [NONE]) has found no match, and nothing is matched. The wording is English; the letter and the names may be in
 * any language, and the question assumes the letter is the one already in the conversation (the Gemma reader's, or a fresh one).
 */
object FollowUpPrompts {

    /** The id of the made-up candidate: choosing it means "none of the real ones", like [NONE]. */
    const val DECOY = "Z"

    /** The id of "none of them". */
    const val NONE = "none"

    /** The organisation's and the contact person's ids in the detail-owner question. */
    const val ORGANISATION = "O"
    const val CONTACT = "C"

    private const val KEY_ANSWER = "answer"
    private const val KEY_ANSWERS = "answers"

    const val YES = "yes"
    const val NO = "no"

    /**
     * One yes/no question about one person: is the letter for or about them? Asked once per household member and once for a made-up
     * person (the calibration: a model that says yes for somebody who cannot be in the letter is not answering reliably). [relation]
     * is how the person is described (null for the made-up one), [printedInFull] a fact code can see, stated as such.
     */
    fun concernedPerson(name: String, relation: String?, printedInFull: Boolean, read: ReadParties = ReadParties.NONE): FollowUpAsk {
        val prompt = buildString {
            append("Question about the letter above: is this letter FOR or ABOUT ").append(name)
            append(" (").append(relation ?: "a person").append(")? It is, when it is addressed to them or concerns them.\n")
            // Facts from this very conversation: what the model itself read as the parties of the letter. The decision stays the model's.
            if (!read.addressee.isNullOrBlank() || !read.sender.isNullOrBlank()) {
                append("In your reading of this letter")
                read.addressee?.takeIf { it.isNotBlank() }?.let { append(": it is addressed to ").append(it) }
                read.sender?.takeIf { it.isNotBlank() }?.let { append(if (read.addressee.isNullOrBlank()) ": " else "; ").append("it was sent by ").append(it) }
                append(".\n")
            }
            if (printedInFull) append("The letter prints this exact name.\n")
            append("Answer $YES or $NO.")
        }
        val schema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        KEY_ANSWER,
                        buildJsonObject {
                            put("type", "string")
                            putJsonArray("enum") { listOf(YES, NO).forEach { add(JsonPrimitive(it)) } }
                        },
                    )
                },
            )
            putJsonArray("required") { add(JsonPrimitive(KEY_ANSWER)) }
            put("additionalProperties", false)
        }.toString()
        return FollowUpAsk(prompt, schema, listOf(YES, NO), multiple = false)
    }

    /** Whether the contact of the letter is one of the known contacts. */
    fun sameContact(q: SameContactQuestion, decoyName: String): FollowUpAsk {
        val ids = q.candidates.indices.map { "C${it + 1}" }
        val prompt = buildString {
            append("Question about the letter above: is its contact person one of the known contacts of the organisation?\n")
            append("Organisation: ").append(q.organisation)
            append("\nContact person of this letter: ")
                .append(ContactFacts.details(q.contact.name, q.contact.title, q.contact.phone, q.contact.email, null))
            append("\nKnown contacts of the organisation:")
            q.candidates.forEachIndexed { i, c ->
                append("\n${ids[i]}: ").append(ContactFacts.details(c.name, c.title, c.phone, c.email, c.lastSeenAt))
                // A hint, never the decision: the model is told what code can see, and the answer stays the model's.
                if (ContactFacts.sameDigits(q.contact.phone, c.phone)) append(" (the phone number is the same as this letter's contact's)")
                if (ContactFacts.sameText(q.contact.email, c.email)) append(" (the e-mail address is the same as this letter's contact's)")
            }
            append("\n$DECOY: $decoyName")
            append("\nAnswer with the id of the known contact who is the same person as the contact person of this letter, only when ")
            append("the details agree. Answer \"$NONE\" when it is somebody else.")
        }
        return FollowUpAsk(prompt, schema(ids, multiple = false), ids, multiple = false)
    }

    /** Whose a phone number, e-mail address, website or bank account of the letter is: the organisation's, the contact person's or neither. */
    fun detailOwner(q: DetailQuestion, decoyName: String): FollowUpAsk {
        val ids = if (q.contactCanOwn) listOf(ORGANISATION, CONTACT) else listOf(ORGANISATION)
        val prompt = buildString {
            append("Question about the letter above: whose is this ").append(thing(q.kind)).append(": ").append(q.value).append("?\n")
            append("$ORGANISATION: the organisation that sent the letter, ").append(q.organisation).append(" (").append(general(q.kind)).append(')')
            if (q.contactCanOwn) append("\n$CONTACT: the contact person ").append(q.contactName).append(" (").append(direct(q.kind)).append(')')
            append("\n$DECOY: $decoyName (somebody unrelated to the letter)")
            append("\nAnswer with the id of the one it belongs to, only when the letter shows it. Answer \"$NONE\" when it is neither.")
        }
        return FollowUpAsk(prompt, schema(ids, multiple = false), ids, multiple = false)
    }

    /** Whether the new letter is part of one of the organisation's matters. */
    fun sameMatter(q: SameMatterQuestion, decoyTitle: String, maxEventsPerMatter: Int): FollowUpAsk {
        val ids = q.candidates.indices.map { "M${it + 1}" }
        val prompt = buildString {
            append("Question about the letter above: is it part of the same matter (an application, a claim, a contract or a case) as one of the ")
            append("matters this organisation has had with the user?\n")
            append("Organisation: ").append(q.organisation)
            append("\nThis letter: ").append(q.event.kindLabel).append(", ").append(ContactFacts.date(q.event.eventDate)).append(": ").append(q.event.title)
            append("\nMatters so far:")
            q.candidates.forEachIndexed { i, c ->
                append("\n${ids[i]}: ").append(c.title)
                val events = c.latestEvents.take(maxEventsPerMatter)
                if (events.isNotEmpty()) append(" (").append(events.joinToString("; ")).append(')')
            }
            append("\n$DECOY: $decoyTitle")
            append("\nLetters about one application, claim, contract or case follow each other over time (filing, receipt, decision, a request ")
            append("for documents, an objection, a rejection), and they do not always carry a common number. Answer with the id of the matter ")
            append("this letter is one more step of. Answer \"$NONE\" only when it is about something else and starts a new matter.")
        }
        return FollowUpAsk(prompt, schema(ids, multiple = false), ids, multiple = false)
    }

    /** The one option the answer chose, or null when it is no JSON object of the schema's shape or names an option that was not offered. */
    fun choice(json: String, ask: FollowUpAsk): String? {
        val value = (root(json)?.get(KEY_ANSWER) as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        return value.takeIf { it in ask.options }
    }

    /** The options the answer chose (without repeats), or null when it is unusable as in [choice]. */
    fun choices(json: String, ask: FollowUpAsk): List<String>? {
        val array = root(json)?.get(KEY_ANSWERS) as? JsonArray ?: return null
        val values = array.map { (it as? JsonPrimitive)?.contentOrNull?.trim() ?: return null }
        return values.takeIf { v -> v.all { it in ask.options } }?.distinct()
    }

    /**
     * What a single choice means: the id of the real candidate it names, or null when it names the made-up one or "none" (no match).
     */
    fun matched(choice: String, ask: FollowUpAsk): String? = choice.takeIf { it in ask.candidateIds }

    /**
     * What several choices mean: the real candidates named, in the offered order. A choice that includes the made-up candidate is
     * not trusted at all (a model that names somebody who cannot be in the letter is guessing), and so names nobody.
     */
    fun matchedAll(choices: List<String>, ask: FollowUpAsk): List<String> =
        if (DECOY in choices) emptyList() else ask.candidateIds.filter { it in choices }

    private fun root(json: String): JsonObject? =
        runCatching { Json { isLenient = true }.parseToJsonElement(json.trim()).jsonObject }.getOrNull()

    private fun schema(candidateIds: List<String>, multiple: Boolean): String {
        val options = candidateIds + DECOY + NONE
        val choice = buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { options.forEach { add(JsonPrimitive(it)) } }
        }
        return buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    if (multiple) {
                        put(
                            KEY_ANSWERS,
                            buildJsonObject {
                                put("type", "array")
                                put("items", choice)
                                // No "uniqueItems": the engine's schema compiler (LLGuidance) does not implement it and refuses the whole schema.
                                put("maxItems", options.size)
                            },
                        )
                    } else {
                        put(KEY_ANSWER, choice)
                    }
                },
            )
            putJsonArray("required") { add(JsonPrimitive(if (multiple) KEY_ANSWERS else KEY_ANSWER)) }
            put("additionalProperties", false)
        }.toString()
    }

    private fun thing(kind: DetailKind): String = when (kind) {
        DetailKind.PHONE -> "telephone number"
        DetailKind.EMAIL -> "e-mail address"
        DetailKind.WEBSITE -> "website"
        DetailKind.IBAN -> "bank account"
    }

    private fun general(kind: DetailKind): String = when (kind) {
        DetailKind.PHONE -> "a general number: a switchboard, a service line or a hotline"
        DetailKind.EMAIL -> "a general address: a central or service mailbox"
        DetailKind.WEBSITE -> "its own website"
        DetailKind.IBAN -> "its own account, the one payments to it go to"
    }

    private fun direct(kind: DetailKind): String = when (kind) {
        DetailKind.PHONE -> "their direct line"
        DetailKind.EMAIL -> "their own address"
        DetailKind.WEBSITE, DetailKind.IBAN -> "personally theirs"
    }
}
