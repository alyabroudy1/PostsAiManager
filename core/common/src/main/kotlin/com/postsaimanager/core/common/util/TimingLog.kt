package com.postsaimanager.core.common.util

/**
 * Diagnostic timing for the chat path. A no-op until a debug build installs [sink] (the Application does, in both processes);
 * so release builds log nothing. [mark] starts a stopwatch, and [at] prefixes a line with the milliseconds since it.
 */
object TimingLog {
    @Volatile
    var sink: ((String) -> Unit)? = null

    @Volatile
    private var markNanos: Long = System.nanoTime()

    fun mark() {
        markNanos = System.nanoTime()
    }

    fun sinceMarkMs(): Long = (System.nanoTime() - markNanos) / 1_000_000

    /** Logs [message] with the time since the last [mark]. */
    fun at(message: String) {
        sink?.invoke("+${sinceMarkMs()}ms $message")
    }

    fun log(message: String) {
        sink?.invoke(message)
    }
}
