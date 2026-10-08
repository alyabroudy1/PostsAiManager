package com.postsaimanager.core.domain.document

import com.postsaimanager.core.model.TimelineEvent

/**
 * A document's "Timeline" tab is its processing log, and every re-read writes the same sentences again (text read, fields found,
 * read again): the log legitimately grows, but a list that shows the same line five times says nothing more than one line with "x5".
 * The entries that say exactly the same (type, code, args, texts, data) become one: the latest of them, with [TimelineEvent.repeats]
 * the number of times it was written. The order of the log is kept.
 */
object CollapseProcessingLog {

    fun collapse(events: List<TimelineEvent>): List<TimelineEvent> {
        val groups = events.groupBy { listOf(it.eventType, it.code, it.args, it.title, it.description, it.data) }
        val latest = groups.mapValues { (_, same) -> same.maxBy { it.createdAt } }
        val kept = HashSet<String>()
        return events.mapNotNull { e ->
            val key = listOf(e.eventType, e.code, e.args, e.title, e.description, e.data)
            val chosen = latest.getValue(key)
            if (e.id == chosen.id && kept.add(e.id)) chosen.copy(repeats = groups.getValue(key).size) else null
        }
    }
}
