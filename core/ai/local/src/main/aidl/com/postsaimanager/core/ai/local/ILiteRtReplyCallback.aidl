package com.postsaimanager.core.ai.local;

/**
 * Streams one LiteRT-LM chat reply back across the process boundary: the tokens of [ITokenCallback], plus the action channel of
 * the Agent Skills. A separate interface so the llama.cpp chat, which has no tools, keeps its own callback unchanged.
 *
 * Every method is `oneway`, as in [ITokenCallback].
 */
oneway interface ILiteRtReplyCallback {
    void onToken(String token);
    void onComplete();
    void onError(String message);

    /**
     * A `run_intent` tool call the model made during the reply: the intent name, the parameters JSON as the model wrote it, and
     * the letter the reply was about (empty for none). Executes nothing: the app process rebuilds the action with
     * `AgentActionParser`, checks it against the letter and shows a card. Arrives before [onComplete].
     */
    void onAction(String intent, String parametersJson, String documentId);
}
