package com.postsaimanager.feature.scanner

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.testing.FakeDocumentProcessor
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.Test

/**
 * Tests for [ScannerViewModel].
 *
 * A scanned document must not sit invisible until someone opens it
 * (documentation/07-document-pipeline.md §7) — these pin that a successful scan enqueues
 * processing before the screen navigates away.
 */
@ExtendWith(MainDispatcherExtension::class)
class ScannerViewModelTest {

    private val repo = FakeDocumentRepository()
    private val documentProcessor = FakeDocumentProcessor()

    private fun viewModel() = ScannerViewModel(repo, documentProcessor)

    /** Uri.toString() isn't stubbed by the Android jar in a plain JVM test. */
    private fun uri(value: String): Uri = mockk<Uri>().also { every { it.toString() } returns value }

    @Test
    fun `a successful scan enqueues processing for the new document`() = runTest {
        val vm = viewModel()

        vm.onScanComplete(listOf(uri("content://page-1")))

        assertThat(vm.uiState.value).isInstanceOf(ScannerUiState.Success::class.java)
        val documentId = (vm.uiState.value as ScannerUiState.Success).documentId
        assertThat(documentProcessor.enqueueCalls).hasSize(1)
        assertThat(documentProcessor.enqueueCalls.single().documentId).isEqualTo(documentId)
        assertThat(documentProcessor.enqueueCalls.single().force).isFalse()
    }

    @Test
    fun `a failed create does not enqueue anything`() = runTest {
        repo.failWith = com.postsaimanager.core.common.result.PamError.DatabaseError()
        val vm = viewModel()

        vm.onScanComplete(listOf(uri("content://page-1")))

        assertThat(vm.uiState.value).isInstanceOf(ScannerUiState.Error::class.java)
        assertThat(documentProcessor.enqueueCalls).isEmpty()
    }

    @Test
    fun `no pages is reported as cancelled, nothing enqueued`() = runTest {
        val vm = viewModel()

        vm.onScanComplete(emptyList())

        assertThat(vm.uiState.value).isInstanceOf(ScannerUiState.Error::class.java)
        assertThat(documentProcessor.enqueueCalls).isEmpty()
    }
}
