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

    /**
     * A tool call the model made during the reply (`load_skill` or `run_intent`) with the result it got back, as JSON objects. The
     * app stores these with the reply so a rebuilt conversation replays the calls ([ToolExchange]). Arrives before [onComplete],
     * in the order the calls were made.
     */
    void onToolExchange(String name, String argumentsJson, String resultJson);

    /**
     * The GPU engine failed this reply before saying anything, so the service reloaded on the CPU and is answering from there.
     * The app records that the GPU cannot run this model, so the next load goes straight to the CPU.
     */
    void onBackendFallback();
}
