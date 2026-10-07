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
     * in the order the calls were made. [shownJson] is what the call put on screen besides its result (a JS skill's webview), or empty.
     */
    void onToolExchange(String name, String argumentsJson, String resultJson, String shownJson);

    /**
     * A `run_js` call: run the script [scriptName] of the skill folder [skillFolder] with [data], offline, in the app process, and
     * answer with `IInferenceService.deliverJsResult` under [requestId]. The reply waits for it, up to a timeout.
     */
    void onRunJs(String requestId, String skillFolder, String scriptName, String data);

    /**
     * The GPU engine failed this reply before saying anything, so the service reloaded on the CPU and is answering from there.
     * The app records that the GPU cannot run this model, so the next load goes straight to the CPU.
     */
    void onBackendFallback();
}
