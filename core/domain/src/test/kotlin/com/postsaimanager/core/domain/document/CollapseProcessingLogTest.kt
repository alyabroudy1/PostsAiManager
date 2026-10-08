package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.TimelineCodes
import com.postsaimanager.core.model.TimelineEvent
import com.postsaimanager.core.model.TimelineEventType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CollapseProcessingLogTest {

    private fun event(id: String, code: String, at: Long, vararg args: String) = TimelineEvent(
        id = id, documentId = "d", eventType = TimelineEventType.TEXT_EXTRACTED, title = code, code = code, args = args.toList(), createdAt = at,
    )

    @Test
    @DisplayName("the log of five re-reads is as long as the log of one: the same sentences become one entry with a count, the latest of them")
    fun `stable`() {
        fun run(n: Int, base: Long) = listOf(
            event("o$n", TimelineCodes.OCR_DONE, base, "1", "90"),
            event("f$n", TimelineCodes.FIELDS_EXTRACTED, base + 1, "9"),
            event("r$n", TimelineCodes.REPROCESSED, base + 2),
        )
        val once = CollapseProcessingLog.collapse(run(1, 0))
        val five = CollapseProcessingLog.collapse((1..5).flatMap { run(it, it * 10L) })

        assertThat(five).hasSize(once.size)
        assertThat(five.map { it.repeats }).containsExactly(5, 5, 5)
        assertThat(five.map { it.id }).containsExactly("o5", "f5", "r5").inOrder()
    }

    @Test
    @DisplayName("entries that differ (another count of fields) stay apart")
    fun `different stay`() {
        val log = CollapseProcessingLog.collapse(
            listOf(event("a", TimelineCodes.FIELDS_EXTRACTED, 1, "9"), event("b", TimelineCodes.FIELDS_EXTRACTED, 2, "11")),
        )

        assertThat(log.map { it.repeats }).containsExactly(1, 1)
    }
}
