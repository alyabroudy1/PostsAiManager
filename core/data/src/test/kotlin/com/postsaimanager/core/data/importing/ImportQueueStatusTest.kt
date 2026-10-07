package com.postsaimanager.core.data.importing

import androidx.work.WorkInfo.State
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.importing.ImportStatus
import org.junit.jupiter.api.Test

/** What the list shows from the job states: "Importing…" while one is unfinished, a failure until it is dismissed. */
class ImportQueueStatusTest {

    @Test
    fun `no jobs is idle`() {
        assertThat(WorkManagerImportQueue.statusOf(emptyList())).isEqualTo(ImportStatus())
        assertThat(WorkManagerImportQueue.statusOf(emptyList()).isIdle).isTrue()
    }

    @Test
    fun `queued, running and blocked jobs are importing`() {
        val status = WorkManagerImportQueue.statusOf(listOf(State.ENQUEUED, State.RUNNING, State.BLOCKED))
        assertThat(status.running).isEqualTo(3)
        assertThat(status.failed).isEqualTo(0)
    }

    @Test
    fun `a failed job is shown until dismissed, a succeeded or cancelled one is not`() {
        val status = WorkManagerImportQueue.statusOf(listOf(State.FAILED, State.SUCCEEDED, State.CANCELLED))
        assertThat(status).isEqualTo(ImportStatus(running = 0, failed = 1))
    }
}
