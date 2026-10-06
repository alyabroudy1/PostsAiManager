package com.postsaimanager.feature.scanner

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.testing.FakeDocumentProcessor
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeUserPreferencesRepository
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
    private val userPreferencesRepository = FakeUserPreferencesRepository()

    private val clock = com.postsaimanager.core.testing.FakeMonotonicClock()
    private val appLock = com.postsaimanager.core.domain.applock.AppLockState(clock)

    private fun viewModel() = ScannerViewModel(
        com.postsaimanager.core.domain.usecase.CreateDocumentFromPagesUseCase(repo, documentProcessor), userPreferencesRepository, appLock,
    )

    /** Uri.toString() isn't stubbed by the Android jar in a plain JVM test. */
    private fun uri(value: String): Uri = mockk<Uri>().also { every { it.toString() } returns value }

    private fun leaveAndReturn() {
        appLock.onBackgrounded()
        clock.advanceMinutes(2)
        appLock.onForegrounded()
    }

    @Test
    fun `returning from the scanner does not lock the app, even at timeout zero`() = runTest {
        appLock.applySettings(enabled = true, timeoutMinutes = 0)
        appLock.unlock()
        val vm = viewModel()

        vm.onScanLaunching()
        leaveAndReturn()

        assertThat(appLock.snapshot.value.locked).isFalse()
    }

    @Test
    fun `a cancelled scan ends the protection`() = runTest {
        appLock.applySettings(enabled = true, timeoutMinutes = 0)
        appLock.unlock()
        val vm = viewModel()

        vm.onScanLaunching()
        vm.onScanCancelled()
        leaveAndReturn()

        assertThat(appLock.snapshot.value.locked).isTrue()
    }

    @Test
    fun `resolving the permission dialog ends its protection`() = runTest {
        appLock.applySettings(enabled = true, timeoutMinutes = 0)
        appLock.unlock()
        val vm = viewModel()

        vm.onNotificationPermissionRequestLaunching()
        vm.onNotificationPermissionResolved()
        leaveAndReturn()

        assertThat(appLock.snapshot.value.locked).isTrue()
    }

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
    fun `a successful scan offers the notification permission prompt when never asked`() = runTest {
        val vm = viewModel()

        vm.onScanComplete(listOf(uri("content://page-1")))

        val state = vm.uiState.value as ScannerUiState.Success
        assertThat(state.offerNotificationPermission).isTrue()
    }

    @Test
    fun `a scan after the prompt was already resolved does not offer it again`() = runTest {
        userPreferencesRepository.setNotificationPermissionRequested(true)
        val vm = viewModel()

        vm.onScanComplete(listOf(uri("content://page-1")))

        val state = vm.uiState.value as ScannerUiState.Success
        assertThat(state.offerNotificationPermission).isFalse()
    }

    @Test
    fun `resolving the prompt remembers it was asked`() = runTest {
        val vm = viewModel()

        vm.onNotificationPermissionResolved()

        assertThat(userPreferencesRepository.current.notificationPermissionRequested).isTrue()
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
