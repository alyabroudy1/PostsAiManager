package com.postsaimanager.core.ai.local;

import com.postsaimanager.core.ai.local.InferenceConfigParcel;
import com.postsaimanager.core.ai.local.ITokenCallback;

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
        long seed,
        String grammar,
        ITokenCallback callback);

    void cancelGeneration();

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
        long seed,
        String grammar,
        boolean noThink,
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
}
