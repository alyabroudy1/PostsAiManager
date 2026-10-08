package com.postsaimanager.core.ai.local;

/**
 * Carries the free-text first turn of a two-turn structured reading ("Gemma reads the letter": the short summary) across the process
 * boundary the moment it is written, while `IInferenceService.generateLiteRtStructured` is still generating the structured answer.
 *
 * `oneway`, as the other callbacks: the model never waits for the app.
 */
oneway interface ILeadCallback {
    void onLead(String text);
}
