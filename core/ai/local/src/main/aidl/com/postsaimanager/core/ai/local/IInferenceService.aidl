package com.postsaimanager.core.ai.local;

import com.postsaimanager.core.ai.local.InferenceConfigParcel;
import com.postsaimanager.core.ai.local.ITokenCallback;
import com.postsaimanager.core.ai.local.ILiteRtReplyCallback;

/**
 * Inference running in a separate process.
 *
 * Spike Q3 measured a native abort inside llama.cpp killing the entire app — SIGABRT is
 * not catchable from Kotlin. Behind this boundary the same abort costs the answer, not the
 * user's documents and unsaved work.
 */
interface IInferenceService {
    /** @return true if the model loaded. */
    boolean loadModel(String modelPath, in InferenceConfigParcel config);

    boolean isReady();

    /**
     * Recreates the context of the resident model with new context/batch/thread/flash-
     * attention settings — ReloadScope.CONTEXT. The model itself is not reloaded.
     * @return true if the context was recreated.
     */
    boolean recreateContext(in InferenceConfigParcel config);

    /** @return true if the model declares its own chat template. */
    boolean hasNativeChatTemplate();

    /** Formats using the model's own template; null if it declares none. */
    String formatChat(in String[] roles, in String[] contents, boolean addAssistant);

    /** Begins streaming to [callback]. Returns false if the prompt was unusable. */
    boolean startGeneration(
        String prompt,
        int maxTokens,
        float temperature,
        int topK,
        float topP,
        float presencePenalty,
        long seed,
        String grammar,
        ITokenCallback callback);

    void cancelGeneration();

    /** True when the last generation stopped at its token cap (not at end-of-generation). */
    boolean lastReplyHitLimit();

    /** Opens a standing chat session — see `LlamaNative.openChatSession`. */
    boolean openChatSession(String systemPrompt);

    /** Bulk-replays persisted history into an opened session — see `LlamaNative.primeChatSession`. */
    boolean primeChatSession(in String[] roles, in String[] contents);

    /** Begins one chat turn and streams the reply to [callback] — see `LlamaNative.sendChatMessage`. */
    boolean sendChatMessage(
        String userText,
        int maxTokens,
        float temperature,
        int topK,
        float topP,
        float presencePenalty,
        long seed,
        String grammar,
        boolean noThink,
        int thinkingBudgetTokens,
        ITokenCallback callback);

    /** Records the assistant's reply in the session's history — see `LlamaNative.commitChatReply`. */
    void commitChatReply(String answer);

    /**
     * Rolls back an interrupted reply's tokens from the KV cache without touching the
     * session's history — see `LlamaNative.discardPendingReply`.
     */
    void discardPendingReply();

    /** Drops the standing chat session — see `LlamaNative.resetChatSession`. */
    void resetChatSession();

    /**
     * Opens a prompt session: decodes [prefix] once — see `LlamaNative.promptOpen`. Returns the
     * prefix's token count, or a negative number on failure.
     */
    int promptOpen(String prefix);

    /**
     * Answers one question of the open prompt session under [grammar] and rolls back to the prefix —
     * see `LlamaNative.promptAsk`. Blocking. Null when the session was lost, the question did not
     * fit, or [cancelGeneration] stopped it.
     */
    String promptAsk(String question, String grammar, int maxTokens);

    /**
     * Scores continuations after the open prompt session's prefix: `logit(yes) - logit(no)` at the last
     * position of each, rolled back after every one — see `LlamaNative.promptScore`. [shared] (empty for
     * none) is decoded once after the prefix and is the level the continuations are rolled back to.
     * Blocking. Null when the session was lost, a continuation did not fit, or [cancelGeneration] stopped it.
     */
    double[] promptScore(String shared, in String[] continuations, String yes, String no);

    /**
     * Scores every head followed by every ask after [shared] (a three-level prefix tree) — see `LlamaNative.promptScoreGrid`.
     * Head-major result. Blocking; null under the same conditions as [promptScore].
     */
    double[] promptScoreGrid(String shared, in String[] heads, in String[] asks, String yes, String no);

    /** Drops the prompt session — see `LlamaNative.promptClose`. */
    void promptClose();

    /** [text] in tokens for the loaded model, or -1 with no model — see `LlamaNative.countTokens`. */
    int countTokens(String text);

    void unloadModel();

    /**
     * Which accelerator types the native backend reports as available on this device —
     * ordinals of `com.postsaimanager.core.model.Accelerator` (0 = CPU, 1 = GPU). Runs the
     * probe even with no model loaded, since it only enumerates `ggml` backend devices.
     */
    int[] availableAccelerators();

    /**
     * Diagnostic: which devices the most recent successful `loadModel` call actually passed
     * to `llama_model_params.devices` — `"CPU"`, `"all"`, or `"none"` if nothing has loaded
     * yet in this process. See `llama_jni.cpp`'s `lastLoadDevices` / `LlamaNative
     * .lastLoadDevices()`. Exists so a test can assert on this without scraping logcat —
     * `LlamaNative` itself is only loaded inside the `:inference` process, so this has to
     * cross the same AIDL boundary as everything else here.
     */
    String lastLoadDevices();

    // ── LiteRT-LM (the AI Edge Gallery's engine): chat only ───────────────────────────────────────────────
    // Hosted by the same process as llama.cpp, and only one of the two holds a model at a time: loading one
    // frees the other. Every call that touches the model runs on the service's single inference thread.

    /**
     * Loads a `.litertlm` model, freeing the resident llama.cpp model first. Returns the accelerator the
     * engine started on (`"GPU"` or `"CPU"`; a GPU request falls back to the CPU), or null when it did not load.
     */
    String loadLiteRtModel(String modelPath, in InferenceConfigParcel config);

    /** True when a LiteRT-LM model is resident. */
    boolean isLiteRtReady();

    /**
     * Opens (or re-primes) the chat session of [conversationId] — see `LiteRtChatEngine.ensureChatSession`. [toolTraces] has one
     * entry per turn: the JSON of the tool calls that turn made (`ToolTrace`), empty for none.
     */
    boolean openLiteRtSession(String conversationId, String systemPrompt, in String[] roles, in String[] contents, in String[] toolTraces);

    /** True when the session of [conversationId] is open and valid. */
    boolean isLiteRtSessionPrimed(String conversationId);

    /**
     * Begins one chat reply and streams it to [callback]. Holds the inference thread until the reply is over, so a
     * llama.cpp call queued behind it (background reading) waits.
     *
     * With [toolsEnabled] the reply may call the Agent Skills tools; each `run_intent` call comes back through
     * [callback]'s `onAction`, carrying [toolsDocumentId] (the letter the reply is about, empty for none).
     */
    boolean sendLiteRtMessage(
        String userText,
        int maxTokens,
        float temperature,
        int topK,
        float topP,
        boolean toolsEnabled,
        String toolsDocumentId,
        ILiteRtReplyCallback callback);

    /** Stops the LiteRT-LM reply in flight, if any. Not queued: it must reach a running reply. */
    void cancelLiteRt();

    /** True when the last LiteRT-LM reply stopped at its token cap. */
    boolean lastLiteRtReplyHitLimit();

    /** Records the finished reply in the session's history. */
    void commitLiteRtReply(String answer);

    /** Drops an interrupted reply from the session without recording it. */
    void discardLiteRtReply();

    /** Drops the chat session. */
    void resetLiteRtSession();

    /** Frees the LiteRT-LM model. */
    void unloadLiteRt();
}
