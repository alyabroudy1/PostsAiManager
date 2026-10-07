package com.postsaimanager.importing

/**
 * Which files an incoming intent asks to import, read from the parts of the intent as plain strings (so it is a JVM test, no
 * `Intent`). The share sheet sends `ACTION_SEND` (one `EXTRA_STREAM`) or `ACTION_SEND_MULTIPLE` (a list, in the sender's order);
 * "Open with" sends `ACTION_VIEW` with the file as the data. Anything else with a clip is read from the clip.
 */
object ImportSources {

    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val ACTION_VIEW = "android.intent.action.VIEW"

    /** The URIs, in order and without repeats. A URI that is not [isReadable] is dropped by [split], not here. */
    fun from(action: String?, stream: String?, streams: List<String>, clip: List<String>, data: String?): List<String> {
        val found = when (action) {
            ACTION_SEND -> listOfNotNull(stream).ifEmpty { clip }
            ACTION_SEND_MULTIPLE -> streams.ifEmpty { clip }
            ACTION_VIEW -> listOfNotNull(data)
            else -> clip
        }
        return found.distinct()
    }

    /**
     * Only a `content://` URI is read. A `file://` URI sent by another app would be opened with this app's own rights, and could
     * name a private file of this app; it is refused.
     */
    fun isReadable(uri: String): Boolean = uri.startsWith("content://")

    /** (readable, refused) in the original order. */
    fun split(uris: List<String>): Pair<List<String>, List<String>> = uris.partition(::isReadable)
}
