package com.postsaimanager.core.download

/** Where one tracked download stands. */
enum class DownloadItemState {
    /** Enqueued, not running: waiting for a connection it may use, or for a free worker. */
    WAITING,

    /** Running, no byte received yet. */
    PREPARING,

    DOWNLOADING,

    DONE,

    FAILED,
    ;

    /** Still going, or going to: keeps the notification alive. */
    val isActive: Boolean get() = this == WAITING || this == PREPARING || this == DOWNLOADING
}

/** One download the notification lists. [totalBytes] is 0 while unknown. */
data class DownloadItem(
    val id: String,
    val name: String,
    val state: DownloadItemState,
    val bytes: Long = 0L,
    val totalBytes: Long = 0L,
) {
    /** 0..100 for a known total, else null. */
    val percent: Int?
        get() = if (totalBytes > 0) ((bytes.coerceIn(0L, totalBytes) * 100) / totalBytes).toInt() else null
}

/**
 * Everything the single download notification says, as pure state: every worker reports into one board, and the notification is
 * rendered from it, so whichever worker posts shows all of them.
 */
data class DownloadBoard(val items: List<DownloadItem> = emptyList()) {

    /** A download was enqueued. An item already running keeps its progress (the work is enqueued with KEEP). */
    fun queued(id: String, name: String, totalBytes: Long): DownloadBoard {
        val existing = items.firstOrNull { it.id == id }
        return if (existing != null && existing.state.isActive) {
            this
        } else {
            upsert(DownloadItem(id, name, DownloadItemState.WAITING, 0L, totalBytes))
        }
    }

    /** The worker runs; [totalBytes] 0 when it does not know it yet (the enqueue-time figure is kept then). */
    fun downloading(id: String, name: String, bytes: Long, totalBytes: Long): DownloadBoard {
        val known = items.firstOrNull { it.id == id }?.totalBytes ?: 0L
        val total = if (totalBytes > 0) totalBytes else known
        val state = if (bytes > 0) DownloadItemState.DOWNLOADING else DownloadItemState.PREPARING
        return upsert(DownloadItem(id, name, state, bytes, total))
    }

    /** The worker stopped to retry later. */
    fun waiting(id: String): DownloadBoard = change(id) { it.copy(state = DownloadItemState.WAITING) }

    fun done(id: String): DownloadBoard = change(id) { it.copy(state = DownloadItemState.DONE, bytes = it.totalBytes) }

    fun failed(id: String): DownloadBoard = change(id) { it.copy(state = DownloadItemState.FAILED) }

    /** Cancelled by the user. */
    fun removed(id: String): DownloadBoard = DownloadBoard(items.filterNot { it.id == id })

    /** True while anything is waiting or running: the notification is shown. */
    val isActive: Boolean get() = items.any { it.state.isActive }

    val itemCount: Int get() = items.size

    val doneCount: Int get() = items.count { it.state == DownloadItemState.DONE }

    /** The 1-based position of the download in progress, for "1 of 3". */
    val currentPosition: Int get() = (doneCount + 1).coerceAtMost(itemCount.coerceAtLeast(1))

    /**
     * Overall 0..100: the bytes of every item whose total is known over the sum of those totals (a finished item counts in full).
     * Null while no total is known.
     */
    val overallPercent: Int?
        get() {
            val known = items.filter { it.totalBytes > 0 }
            val total = known.sumOf { it.totalBytes }
            if (total <= 0) return null
            val got = known.sumOf { if (it.state == DownloadItemState.DONE) it.totalBytes else it.bytes.coerceIn(0L, it.totalBytes) }
            return ((got * 100) / total).toInt()
        }

    private fun upsert(item: DownloadItem): DownloadBoard =
        if (items.any { it.id == item.id }) DownloadBoard(items.map { if (it.id == item.id) item else it }) else DownloadBoard(items + item)

    private fun change(id: String, transform: (DownloadItem) -> DownloadItem): DownloadBoard =
        DownloadBoard(items.map { if (it.id == id) transform(it) else it })
}

/** Lets an update through at most once per [minIntervalMs]; the first always passes. Pure: time comes in as an argument. */
class ProgressThrottle(private val minIntervalMs: Long) {
    private var last = Long.MIN_VALUE

    @Synchronized
    fun allow(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs - last < minIntervalMs) return false
        last = nowMs
        return true
    }
}
