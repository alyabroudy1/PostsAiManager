package com.postsaimanager.core.common.notification

/**
 * Where a tapped notification leads. The one vocabulary shared by whoever posts a notification (workers, the download
 * centre) and the app that navigates; it is carried in the intent as a `postsaimanager://` URI, see [toUri] and [parse].
 *
 * Pure Kotlin on purpose (no `android.net.Uri`), so building and parsing are unit-testable on the JVM.
 */
sealed interface NotificationRoute {

    /** One document's detail screen. */
    data class Document(val documentId: String) : NotificationRoute

    /** The document list: several documents at once, or a document that no longer exists. */
    data object Documents : NotificationRoute

    /** Model downloads. The app decides at tap time: the setup while it is still in progress, otherwise the Models screen. */
    data object Downloads : NotificationRoute

    /** The Models screen. */
    data object Models : NotificationRoute

    /** The first-run setup. */
    data object Setup : NotificationRoute

    fun toUri(): String = when (this) {
        is Document -> "$PREFIX$HOST_DOCUMENT/${encode(documentId)}"
        Documents -> "$PREFIX$HOST_DOCUMENTS"
        Downloads -> "$PREFIX$HOST_DOWNLOADS"
        Models -> "$PREFIX$HOST_MODELS"
        Setup -> "$PREFIX$HOST_SETUP"
    }

    companion object {
        const val SCHEME = "postsaimanager"
        private const val PREFIX = "$SCHEME://"
        private const val HOST_DOCUMENT = "document"
        private const val HOST_DOCUMENTS = "documents"
        private const val HOST_DOWNLOADS = "downloads"
        private const val HOST_MODELS = "models"
        private const val HOST_SETUP = "setup"

        /** The route a URI string stands for; null for anything that is not one of ours (never guesses). */
        fun parse(uri: String?): NotificationRoute? {
            if (uri == null || !uri.startsWith(PREFIX)) return null
            val rest = uri.removePrefix(PREFIX).substringBefore('?').substringBefore('#').trimEnd('/')
            val host = rest.substringBefore('/')
            val tail = rest.substringAfter('/', "")
            return when (host) {
                HOST_DOCUMENT -> decode(tail)?.takeIf { it.isNotBlank() && '/' !in tail }?.let(::Document)
                HOST_DOCUMENTS -> Documents.takeIf { tail.isEmpty() }
                HOST_DOWNLOADS -> Downloads.takeIf { tail.isEmpty() }
                HOST_MODELS -> Models.takeIf { tail.isEmpty() }
                HOST_SETUP -> Setup.takeIf { tail.isEmpty() }
                else -> null
            }
        }

        /** Percent-encodes everything but unreserved characters, so an id can never add a path segment or a query. */
        private fun encode(value: String): String = buildString {
            value.toByteArray(Charsets.UTF_8).forEach { b ->
                val c = (b.toInt() and 0xFF).toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") append(c) else append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }

        private fun decode(value: String): String? = runCatching { java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrNull()
    }
}
