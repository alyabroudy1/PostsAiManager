package com.postsaimanager.core.domain.importing

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.stagedFile
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * "You added this file on 5 Oct": a byte-identical earlier import is found. One in Recently deleted is found too, marked with the day
 * it was deleted (so the sheet can offer the restore); a cut-short import without pages and a different file are not.
 */
class FindImportedDuplicateUseCaseTest {

    private val repository = FakeDocumentRepository()
    private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)
    private val useCase = FindImportedDuplicateUseCase(repository, clock)

    private val oct5 = Instant.parse("2026-10-05T09:30:00Z").toEpochMilli()
    private val oct6 = Instant.parse("2026-10-06T09:30:00Z").toEpochMilli()
    private val group = ImportGroup(listOf(stagedFile("a", ImportedKind.PDF, sha = "hash-1")))

    @Test
    fun `an earlier import of the same file is found with the day it was added`() = runTest {
        repository.seedImported(testDocument(id = "old", createdAt = oct5).copy(sourceHash = "hash-1"))

        val found = useCase(group)

        assertThat(found).isEqualTo(ImportedDuplicate("old", LocalDate.of(2026, 10, 5)))
        assertThat(found?.isTrashed).isFalse()
    }

    @Test
    fun `a different file is not a duplicate`() = runTest {
        repository.seedImported(testDocument(id = "other", createdAt = oct5).copy(sourceHash = "hash-2"))
        assertThat(useCase(group)).isNull()
    }

    @Test
    fun `a scan without a hash is never a duplicate`() = runTest {
        repository.seedImported(testDocument(id = "scan", createdAt = oct5))
        assertThat(useCase(group)).isNull()
    }

    @Test
    fun `an import in Recently deleted is reported with the day it was deleted`() = runTest {
        repository.seedImported(testDocument(id = "gone", createdAt = oct5, deletedAt = oct6).copy(sourceHash = "hash-1"))

        val found = useCase(group)

        assertThat(found).isEqualTo(ImportedDuplicate("gone", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6)))
        assertThat(found?.isTrashed).isTrue()
    }

    @Test
    fun `a live import wins over a trashed one`() = runTest {
        repository.seedImported(
            testDocument(id = "trashed", createdAt = oct5, deletedAt = oct6).copy(sourceHash = "hash-1"),
            testDocument(id = "live", createdAt = oct6).copy(sourceHash = "hash-1"),
        )
        assertThat(useCase(group)?.documentId).isEqualTo("live")
    }

    @Test
    fun `an import cut short, a row without pages, does not block the file`() = runTest {
        repository.seed(testDocument(id = "half", createdAt = oct5).copy(sourceHash = "hash-1"))
        assertThat(useCase(group)).isNull()
    }

    @Test
    fun `the earliest of several is reported`() = runTest {
        repository.seedImported(
            testDocument(id = "later", createdAt = oct5 + 86_400_000).copy(sourceHash = "hash-1"),
            testDocument(id = "first", createdAt = oct5).copy(sourceHash = "hash-1"),
        )
        assertThat(useCase(group)?.documentId).isEqualTo("first")
    }

    @Test
    fun `a group of images is found by its combined hash`() = runTest {
        val images = ImportGroup(listOf(stagedFile("i1"), stagedFile("i2")))
        repository.seedImported(testDocument(id = "set", createdAt = oct5).copy(sourceHash = images.sourceHash))

        assertThat(useCase(images)?.documentId).isEqualTo("set")
        assertThat(useCase(ImportGroup(listOf(stagedFile("i2"), stagedFile("i1"))))).isNull()
    }
}
