package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.domain.skills.ActionParse
import com.postsaimanager.core.domain.skills.AgentActionParser

/**
 * What a reply may use tools for: asked of a [ChatEngine] by [AiRequest.tools], which only a tool-capable engine acts on.
 *
 * @param documentId the letter this reply is about, when one letter is (the chat is about it, or every passage the reply was
 *   grounded on comes from it). It is what a `schedule_notification` without a document id opens, and what the card's values
 *   are checked against. Null: no letter grounding, and the card says so.
 */
data class ChatToolsRequest(val documentId: String?)

/**
 * One `run_intent` call the model made, as it crosses the process boundary: the intent name and the parameters JSON exactly as
 * the model wrote them, and the letter the reply was about. The `AgentAction` is NOT carried: the main process rebuilds it with
 * the pure [AgentActionParser], so no Parcelable per action kind exists and the model's own words are what is checked.
 */
data class ToolActionCall(
    val intent: String,
    val parametersJson: String,
    val documentId: String?,
) {
    /** What this call means, parsed with the one parser the service's tool used too. */
    fun parse(): ActionParse = AgentActionParser.parse(intent, parametersJson, documentId)
}

/**
 * The wire form of a [ToolActionCall] over AIDL, which has no nullable-free way to say "no document": three strings, the third
 * empty for none. A pure mapper, so the round trip is tested without a Binder.
 */
object ToolActionWire {

    fun documentIdToWire(documentId: String?): String = documentId.orEmpty()

    /** The call the three wire strings say, or null when the intent is missing (nothing to act on). */
    fun fromWire(intent: String?, parametersJson: String?, documentId: String?): ToolActionCall? {
        val name = intent?.trim().orEmpty()
        if (name.isEmpty()) return null
        return ToolActionCall(
            intent = name,
            parametersJson = parametersJson.orEmpty(),
            documentId = documentId?.takeIf { it.isNotBlank() },
        )
    }
}
