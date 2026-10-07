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

/** "You added this file on 5 Oct": a byte-identical earlier import is found, a trashed one and a different file are not. */
class FindImportedDuplicateUseCaseTest {

    private val repository = FakeDocumentRepository()
    private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)
    private val useCase = FindImportedDuplicateUseCase(repository, clock)

    private val oct5 = Instant.parse("2026-10-05T09:30:00Z").toEpochMilli()
    private val group = ImportGroup(listOf(stagedFile("a", ImportedKind.PDF, sha = "hash-1")))

    @Test
    fun `an earlier import of the same file is found with the day it was added`() = runTest {
        repository.seed(testDocument(id = "old", createdAt = oct5).copy(sourceHash = "hash-1"))

        val found = useCase(group)

        assertThat(found).isEqualTo(ImportedDuplicate("old", LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `a different file is not a duplicate`() = runTest {
        repository.seed(testDocument(id = "other", createdAt = oct5).copy(sourceHash = "hash-2"))
        assertThat(useCase(group)).isNull()
    }

    @Test
    fun `a scan without a hash is never a duplicate`() = runTest {
        repository.seed(testDocument(id = "scan", createdAt = oct5))
        assertThat(useCase(group)).isNull()
    }

    @Test
    fun `a trashed import does not count`() = runTest {
        repository.seed(testDocument(id = "gone", createdAt = oct5, deletedAt = oct5 + 1).copy(sourceHash = "hash-1"))
        assertThat(useCase(group)).isNull()
    }

    @Test
    fun `the earliest of several is reported`() = runTest {
        repository.seed(
            testDocument(id = "later", createdAt = oct5 + 86_400_000).copy(sourceHash = "hash-1"),
            testDocument(id = "first", createdAt = oct5).copy(sourceHash = "hash-1"),
        )
        assertThat(useCase(group)?.documentId).isEqualTo("first")
    }

    @Test
    fun `a group of images is found by its combined hash`() = runTest {
        val images = ImportGroup(listOf(stagedFile("i1"), stagedFile("i2")))
        repository.seed(testDocument(id = "set", createdAt = oct5).copy(sourceHash = images.sourceHash))

        assertThat(useCase(images)?.documentId).isEqualTo("set")
        assertThat(useCase(ImportGroup(listOf(stagedFile("i2"), stagedFile("i1"))))).isNull()
    }
}
