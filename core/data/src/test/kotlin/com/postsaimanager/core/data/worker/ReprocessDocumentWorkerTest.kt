package com.postsaimanager.core.data.worker

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.model.DocumentStatus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ReprocessDocumentWorkerTest {

    private fun doc(
        status: DocumentStatus = DocumentStatus.EXTRACTED,
        version: String? = null,
        deletedAt: Long? = null,
    ) = DocumentEntity(
        id = "d1", title = "t", status = status.name, documentType = null, language = null,
        sourceType = "CAMERA", thumbnailPath = null, pageCount = 1, createdAt = 0L, modifiedAt = 0L,
        deletedAt = deletedAt, extractorVersion = version,
    )

    @Test
    @DisplayName("charging OR idle is two requests, each also requiring the battery not to be low")
    fun constraints() {
        val charging = ReprocessDocumentWorker.chargingConstraints
        assertThat(charging.requiresCharging()).isTrue()
        assertThat(charging.requiresDeviceIdle()).isFalse()
        assertThat(charging.requiresBatteryNotLow()).isTrue()

        // requiresDeviceIdle is only recorded by WorkManager's builder on API 23+, and this JVM test
        // runs with SDK_INT 0; the idle request's constraints are asserted in ReprocessConstraintsTest
        // (androidTest).
        val idle = ReprocessDocumentWorker.idleConstraints
        assertThat(idle.requiresCharging()).isFalse()
        assertThat(idle.requiresBatteryNotLow()).isTrue()
    }

    @Test
    @DisplayName("reprocess work has its own unique names, never a scan's process-document-<id>")
    fun namesNeverCollideWithScans() {
        val scan = DocumentProcessingWorker.workName("d1")
        val names = listOf(
            ReprocessDocumentWorker.chargingWorkName("d1"),
            ReprocessDocumentWorker.idleWorkName("d1"),
        )

        assertThat(names).containsNoDuplicates()
        assertThat(names).doesNotContain(scan)
        names.forEach { assertThat(it).startsWith("reprocess-document-") }
        assertThat(ReprocessDocumentWorker.chargingWorkName("d1")).isEqualTo("reprocess-document-d1")
    }

    @Test
    @DisplayName("both requests target the same document and carry their own constraints")
    fun requests() {
        val requests = ReprocessDocumentWorker.requests("d1")

        assertThat(requests.map { it.first }).containsExactly("reprocess-document-d1", "reprocess-document-d1-idle")
        requests.forEach { (_, request) ->
            assertThat(request.workSpec.input.getString(ReprocessDocumentWorker.KEY_DOCUMENT_ID)).isEqualTo("d1")
        }
        assertThat(requests[0].second.workSpec.constraints.requiresCharging()).isTrue()
        assertThat(requests[1].second.workSpec.constraints.requiresCharging()).isFalse()
    }

    @Test
    @DisplayName("a new scan going first: the run defers while one is queued or running")
    fun defersForScans() {
        assertThat(ReprocessGate.decide(doc(), scansInFlight = true)).isEqualTo(ReprocessGate.Decision.DEFER)
        assertThat(ReprocessGate.decide(doc(), scansInFlight = false)).isEqualTo(ReprocessGate.Decision.RUN)
    }

    @Test
    @DisplayName("nothing to do when the letter is gone, trashed, unfinished or already current")
    fun skips() {
        assertThat(ReprocessGate.decide(null, false)).isEqualTo(ReprocessGate.Decision.SKIP)
        assertThat(ReprocessGate.decide(doc(deletedAt = 1L), false)).isEqualTo(ReprocessGate.Decision.SKIP)
        assertThat(ReprocessGate.decide(doc(status = DocumentStatus.FAILED), false)).isEqualTo(ReprocessGate.Decision.SKIP)
        assertThat(ReprocessGate.decide(doc(version = ExtractorVersion.CURRENT), false))
            .isEqualTo(ReprocessGate.Decision.SKIP)
    }
}
