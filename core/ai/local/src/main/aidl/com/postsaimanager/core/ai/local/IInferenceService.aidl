package com.postsaimanager.core.ai.local;

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
    boolean loadModel(String modelPath, int contextTokens, int threads);

    boolean isReady();

    /** @return true if the model declares its own chat template. */
    boolean hasNativeChatTemplate();

    /** Formats using the model's own template; null if it declares none. */
    String formatChat(in String[] roles, in String[] contents, boolean addAssistant);

    /** Begins streaming to [callback]. Returns false if the prompt was unusable. */
    boolean startGeneration(
        String prompt,
        int maxTokens,
        float temperature,
        String grammar,
        ITokenCallback callback);

    void cancelGeneration();

    void unloadModel();
}
