package com.postsaimanager.importing

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.importing.FindImportedDuplicateUseCase
import com.postsaimanager.core.domain.importing.ImportProblem
import com.postsaimanager.core.domain.importing.ImportResult
import com.postsaimanager.core.domain.importing.ImportedKind
import com.postsaimanager.core.domain.importing.StageResult
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeImportQueue
import com.postsaimanager.core.testing.FakePageImageSource
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.testing.stagedFile
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The confirm sheet's state: staging, grouping, duplicates, passwords, and what "Add to my letters" hands to the background job. */
@ExtendWith(MainDispatcherExtension::class)
class ImportViewModelTest {

    private val source = FakePageImageSource()
    private val queue = FakeImportQueue()
    private val repository = FakeDocumentRepository()
    private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)

    private fun viewModel() = ImportViewModel(source, queue, FindImportedDuplicateUseCase(repository, clock)).also {
        it.cleanupScope = CoroutineScope(Dispatchers.Unconfined)
    }

    private val pdf = "content://p/report.pdf"
    private val img1 = "content://p/one.jpg"
    private val img2 = "content://p/two.jpg"
    private val img3 = "content://p/three.jpg"

    private fun stagePdf(uri: String, pages: Int = 3, sha: String = "sha-$uri") {
        source.stageResults[uri] = StageResult.Staged(stagedFile(uri.substringAfterLast('/'), ImportedKind.PDF, pages, sha))
    }

    private fun ImportViewModel.rowFiles() = state.value.rows.map { row -> row.group.files.map { it.id } }

    @Test
    fun `starting stages what was shared and lands on the review`() {
        stagePdf(pdf)
        val vm = viewModel()

        assertThat(vm.state.value.stage).isEqualTo(ImportStage.READING)
        vm.start(listOf(pdf, img1))

        assertThat(source.staged).containsExactly(pdf, img1).inOrder()
        assertThat(vm.state.value.stage).isEqualTo(ImportStage.REVIEW)
        assertThat(vm.state.value.files.map { it.id }).containsExactly("report.pdf", "one.jpg").inOrder()
        assertThat(vm.state.value.canAdd).isTrue()
    }

    @Test
    fun `starting twice reads the files once`() {
        val vm = viewModel()
        vm.start(listOf(img1))
        vm.start(listOf(img1))
        assertThat(source.staged).containsExactly(img1)
    }

    @Test
    fun `a file URI is refused and never opened`() {
        val vm = viewModel()
        vm.start(listOf("file:///data/data/x/secret.pdf", img1))

        assertThat(source.staged).containsExactly(img1)
        assertThat(vm.state.value.problems).hasSize(1)
        assertThat(vm.state.value.problems.single()).isInstanceOf(ImportProblem.NotSupported::class.java)
    }

    @Test
    fun `a rejected file is listed with its reason and the others stay`() {
        source.stageResults[img2] = StageResult.Rejected(ImportProblem.TooLarge("two.jpg"))
        val vm = viewModel()
        vm.start(listOf(img1, img2))

        assertThat(vm.state.value.problems).containsExactly(ImportProblem.TooLarge("two.jpg"))
        assertThat(vm.rowFiles()).containsExactly(listOf("one.jpg"))
    }

    @Test
    fun `only rejected files leave nothing to add`() {
        source.stageResults[pdf] = StageResult.Rejected(ImportProblem.TooManyPages("report.pdf", 60))
        val vm = viewModel()
        vm.start(listOf(pdf))

        assertThat(vm.state.value.stage).isEqualTo(ImportStage.REVIEW)
        assertThat(vm.state.value.rows).isEmpty()
        assertThat(vm.state.value.canAdd).isFalse()
    }

    @Test
    fun `images together are one document and the switch splits them, shown only for two or more`() {
        val vm = viewModel()
        vm.start(listOf(img1))
        assertThat(vm.state.value.showSeparateSwitch).isFalse()

        val several = viewModel()
        several.start(listOf(img1, img2, img3))
        assertThat(several.state.value.showSeparateSwitch).isTrue()
        assertThat(several.rowFiles()).containsExactly(listOf("one.jpg", "two.jpg", "three.jpg"))

        several.setEachImageSeparate(true)
        assertThat(several.rowFiles()).containsExactly(listOf("one.jpg"), listOf("two.jpg"), listOf("three.jpg")).inOrder()
        several.setEachImageSeparate(false)
        assertThat(several.rowFiles()).hasSize(1)
    }

    @Test
    fun `in a mix each PDF is its own document and the images go together`() {
        stagePdf(pdf)
        val vm = viewModel()
        vm.start(listOf(img1, pdf, img2))

        assertThat(vm.rowFiles()).containsExactly(listOf("one.jpg", "two.jpg"), listOf("report.pdf")).inOrder()
    }

    @Test
    fun `thumbnails arrive per file`() {
        stagePdf(pdf)
        val vm = viewModel()
        vm.start(listOf(pdf))

        assertThat(vm.state.value.rows.single().thumbnails).containsExactly("report.pdf", "file:///thumbs/report.pdf.jpg")
    }

    @Test
    fun `a file added before is flagged with its day and left out until added again`() {
        val oct5 = Instant.parse("2026-10-05T09:30:00Z").toEpochMilli()
        repository.seed(testDocument(id = "old", createdAt = oct5).copy(sourceHash = "same-hash"))
        stagePdf(pdf, sha = "same-hash")
        val vm = viewModel()
        vm.start(listOf(pdf))

        val row = vm.state.value.rows.single()
        assertThat(row.duplicate?.addedOn).isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(row.included).isFalse()
        assertThat(vm.state.value.canAdd).isFalse()

        vm.setAddAgain(row.group, true)
        assertThat(vm.state.value.rows.single().included).isTrue()
        assertThat(vm.state.value.canAdd).isTrue()

        vm.setAddAgain(row.group, false)
        assertThat(vm.state.value.canAdd).isFalse()
    }

    @Test
    fun `a duplicate does not stop the new files in the same share`() {
        repository.seed(testDocument(id = "old").copy(sourceHash = "same-hash"))
        stagePdf(pdf, sha = "same-hash")
        val vm = viewModel()
        vm.start(listOf(pdf, img1))

        assertThat(vm.state.value.rows.map { it.included }).containsExactly(false, true).inOrder()
        vm.confirm()
        assertThat(queue.submitted.single().groups.map { g -> g.files.map { it.id } }).containsExactly(listOf("one.jpg"))
    }

    @Test
    fun `a locked PDF waits for its password and is not part of any document yet`() {
        source.stageResults[pdf] = StageResult.Staged(stagedFile("report.pdf", ImportedKind.PDF, passwordRequired = true))
        val vm = viewModel()
        vm.start(listOf(pdf, img1))

        assertThat(vm.state.value.lockedFiles.map { it.id }).containsExactly("report.pdf")
        assertThat(vm.rowFiles()).containsExactly(listOf("one.jpg"))
    }

    @Test
    fun `a wrong password is marked and the file stays locked`() {
        source.stageResults[pdf] = StageResult.Staged(stagedFile("report.pdf", ImportedKind.PDF, passwordRequired = true))
        source.unlockResult = { file, _ -> StageResult.Rejected(ImportProblem.WrongPassword(file.displayName)) }
        val vm = viewModel()
        vm.start(listOf(pdf))

        vm.unlock("report.pdf", "nope")

        assertThat(vm.state.value.wrongPasswords).containsExactly("report.pdf")
        assertThat(vm.state.value.lockedFiles).hasSize(1)
        assertThat(vm.state.value.rows).isEmpty()
    }

    @Test
    fun `the right password turns the file into a document and travels with the request`() {
        source.stageResults[pdf] = StageResult.Staged(stagedFile("report.pdf", ImportedKind.PDF, passwordRequired = true))
        val vm = viewModel()
        vm.start(listOf(pdf))

        vm.unlock("report.pdf", "secret")

        assertThat(vm.state.value.lockedFiles).isEmpty()
        assertThat(vm.rowFiles()).containsExactly(listOf("report.pdf"))
        assertThat(vm.state.value.rows.single().group.pageCount).isEqualTo(2)

        vm.confirm()
        assertThat(queue.submitted.single().passwords).containsExactly("report.pdf", "secret")
    }

    @Test
    fun `a password that opens a PDF with too many pages drops the file with the reason`() {
        source.stageResults[pdf] = StageResult.Staged(stagedFile("report.pdf", ImportedKind.PDF, passwordRequired = true))
        source.unlockResult = { file, _ -> StageResult.Rejected(ImportProblem.TooManyPages(file.displayName, 70)) }
        val vm = viewModel()
        vm.start(listOf(pdf))

        vm.unlock("report.pdf", "secret")

        assertThat(vm.state.value.files).isEmpty()
        assertThat(vm.state.value.problems).containsExactly(ImportProblem.TooManyPages("report.pdf.pdf", 70))
    }

    @Test
    fun `adding hands the included documents to the background job and opens the new document`() {
        stagePdf(pdf)
        queue.result = ImportResult(listOf("new-doc"), failedGroups = 0)
        val vm = viewModel()
        vm.start(listOf(pdf))

        vm.confirm()

        val request = queue.submitted.single()
        assertThat(request.groups.map { g -> g.files.map { it.id } }).containsExactly(listOf("report.pdf"))
        assertThat(vm.state.value.submitted).isTrue()
        assertThat(vm.state.value.target).isEqualTo(ImportTarget.OpenDocument("new-doc"))
    }

    @Test
    fun `several documents open the list`() {
        stagePdf(pdf)
        queue.result = ImportResult(listOf("d1", "d2"), failedGroups = 0)
        val vm = viewModel()
        vm.start(listOf(pdf, img1))

        vm.confirm()

        assertThat(vm.state.value.target).isEqualTo(ImportTarget.OpenList)
    }

    @Test
    fun `one document of two that worked opens the list, not a single document`() {
        stagePdf(pdf)
        queue.result = ImportResult(listOf("d1"), failedGroups = 1)
        val vm = viewModel()
        vm.start(listOf(pdf, img1))

        vm.confirm()

        assertThat(vm.state.value.target).isEqualTo(ImportTarget.OpenList)
    }

    @Test
    fun `when nothing could be made the failure is shown`() {
        queue.result = ImportResult(emptyList(), failedGroups = 1)
        val vm = viewModel()
        vm.start(listOf(img1))

        vm.confirm()

        assertThat(vm.state.value.target).isEqualTo(ImportTarget.Failed)
    }

    @Test
    fun `while the job runs the person can continue in the background and land on the list`() {
        queue.gate = CompletableDeferred()
        val vm = viewModel()
        vm.start(listOf(img1))

        vm.hide()
        assertThat(vm.state.value.target).isNull()

        vm.confirm()
        assertThat(vm.state.value.stage).isEqualTo(ImportStage.ADDING)
        assertThat(vm.state.value.submitted).isTrue()
        vm.hide()
        assertThat(vm.state.value.target).isEqualTo(ImportTarget.OpenList)
        // leaving did not discard what the job still needs
        assertThat(source.discarded).isEmpty()
    }

    @Test
    fun `nothing is submitted when nothing is included`() {
        val vm = viewModel()
        vm.start(emptyList())

        vm.confirm()

        assertThat(queue.submitted).isEmpty()
    }

    @Test
    fun `cancel imports nothing and removes every temporary copy`() {
        val vm = viewModel()
        vm.start(listOf(img1))

        vm.cancel()

        assertThat(queue.submitted).isEmpty()
        assertThat(source.discarded).hasSize(1)
        assertThat(vm.state.value.target).isEqualTo(ImportTarget.Close)
    }
}
