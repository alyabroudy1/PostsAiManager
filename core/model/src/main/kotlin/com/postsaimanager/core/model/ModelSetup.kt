package com.postsaimanager.core.model

/** Where one of the two first-run downloads (the chat model, the search model) stands. */
sealed interface SetupPartStatus {
    data object NotStarted : SetupPartStatus

    /** Queued: waiting for a connection the download is allowed to use (Wi-Fi, unless the user agreed to mobile data). */
    data object Waiting : SetupPartStatus

    data class Downloading(val bytesDone: Long, val totalBytes: Long?) : SetupPartStatus {
        /** 0..1, or null when the total is not known yet. */
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDone.toFloat() / it).coerceIn(0f, 1f) }
    }

    data object Done : SetupPartStatus

    data object Failed : SetupPartStatus
}

/** What first-run setup would download, for the screen to describe before the user agrees. */
data class SetupOffer(
    val chatModelName: String,
    val chatModelBytes: Long,
    val searchModelBytes: Long,
    /** False when this phone cannot run the chat model (too little memory or storage, unsupported processor). */
    val canInstallChatModel: Boolean,
)

/** Both downloads together. */
data class SetupProgress(
    val chat: SetupPartStatus,
    val search: SetupPartStatus,
) {
    val isComplete: Boolean get() = chat is SetupPartStatus.Done && search is SetupPartStatus.Done

    val anyFailed: Boolean get() = chat is SetupPartStatus.Failed || search is SetupPartStatus.Failed

    val noneStarted: Boolean
        get() = chat is SetupPartStatus.NotStarted && search is SetupPartStatus.NotStarted

    companion object {
        val NOT_STARTED = SetupProgress(SetupPartStatus.NotStarted, SetupPartStatus.NotStarted)
    }
}

/** Whether the app needs the user to set up the AI model, when it opens. */
enum class SetupNeed {
    /** No chat model is installed and the user has not postponed: show the setup screen. */
    REQUIRED,

    /** No chat model is installed but the user chose "Skip for now": go home and show the banner. */
    SKIPPED,

    /** A chat model is installed. */
    NOT_NEEDED,
}
