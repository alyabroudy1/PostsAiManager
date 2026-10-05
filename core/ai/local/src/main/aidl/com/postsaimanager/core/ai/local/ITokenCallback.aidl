package com.postsaimanager.core.ai.local;

/**
 * Streams generated tokens back across the process boundary.
 *
 * Every method is `oneway`: the inference process must never block waiting on the caller,
 * or a slow collector would stall generation.
 */
oneway interface ITokenCallback {
    void onToken(String token);
    void onComplete();
    void onError(String message);
}
